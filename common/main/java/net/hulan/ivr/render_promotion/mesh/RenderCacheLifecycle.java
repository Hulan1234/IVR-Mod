package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.*;
import net.hulan.ivr.render_promotion.bounds.TrainBoundsProvider;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理客户端线程生命周期与有界结果发布；GPU 资源由版本后端按需接管。 */
public final class RenderCacheLifecycle {
    private static final VertexMeshCache CACHE = new VertexMeshCache(256, 8L * 1024 * 1024);
    private static final MeshBuildScheduler BUILDER = new MeshBuildScheduler();
    private static Object world;
    private static long generation;
    private static boolean reloading;
    private static int reloads;
    private static final long RETRY_DELAY_NANOS = 250_000_000L;
    private static final int MAX_RETRY_ENTRIES = 4096;
    private static final Map<VertexMeshKey, Long> RETRY_AFTER = new LinkedHashMap<>();

    private static void deferRetry(VertexMeshKey key) {
        if (key == null) return;
        RETRY_AFTER.put(key, System.nanoTime() + RETRY_DELAY_NANOS);
        while (RETRY_AFTER.size() > MAX_RETRY_ENTRIES) {
            RETRY_AFTER.remove(RETRY_AFTER.keySet().iterator().next());
        }
    }

    private static void checkThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Client thread required");
    }

    public static void beginFrame(Object currentWorld) {
        checkThread();
        TrainBoundsProvider.getInstance().beginFrame();
        GpuInstanceBatch.beginFrame();
        if (world != currentWorld) {
            invalidate();
            world = currentWorld;
            TrainBoundsProvider.getInstance().clear();
        }
    }
    public static VertexMesh request(VertexMeshKey key, List<VertexMesh.Vertex> vertices) {
        checkThread();
        if (!preparationEnabled()) return null;
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh == null && !reloading) {
            if (shouldAttemptBuild(key)) {
                if (!BUILDER.request(new MeshBuildScheduler.Ticket(key, generation, key.revision()), vertices)) {
                    deferRetry(key);
                } else {
                    RETRY_AFTER.remove(key);
                }
            }
        }
        return mesh;
    }
    public static VertexMesh get(VertexMeshKey key) {
        checkThread();
        if (!IVRConfig.cpuOptimization) return null;
        return getPrepared(key);
    }

    /** Returns prepared geometry for either CPU replay or GPU consumption. */
    public static VertexMesh getPrepared(VertexMeshKey key) {
        checkThread();
        if (!preparationEnabled()) return null;
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh == null) CacheAdmission.recordMiss(key);
        else CacheAdmission.recordHit(key);
        if (mesh != null && IVRConfig.gpuOptimization && !GpuMeshCache.isPublished(key, mesh)) {
            try {
                GpuMeshCache.publish(key, mesh);
                RenderDebugStats.recordGpuMeshReady();
            } catch (RuntimeException ignored) {
                // 版本后端无法接管网格或当前 GL 上下文不可用时，仍可重放 CPU 网格。
            }
        }
        return mesh;
    }

    /** Publishes a render-thread mesh captured from a stable EyeCandy output. */
    public static VertexMesh publishImmediate(VertexMeshKey key, VertexMesh mesh) {
        checkThread();
        if (!preparationEnabled()) return null;
        if (!isCurrentKey(key) || mesh == null) {
            return null;
        }
        CACHE.put(key, mesh);
        if (IVRConfig.gpuOptimization) try {
            GpuMeshCache.publish(key, mesh);
            RenderDebugStats.recordGpuMeshReady();
        } catch (RuntimeException ignored) {
            // CPU replay remains valid when the current version has no usable GL context.
        }
        return mesh;
    }

    public static long generation() {
        checkThread();
        return generation;
    }

    public static VertexMesh publishImmediateIfCurrent(VertexMeshKey key, VertexMesh mesh,
                                                        long expectedGeneration) {
        checkThread();
        if (!preparationEnabled()) return null;
        if (mesh == null || expectedGeneration != generation || !isCurrentKey(key)) {
            return null;
        }
        CACHE.put(key, mesh);
        if (expectedGeneration != generation || !isCurrentKey(key)) {
            CACHE.removeIfSame(key, mesh);
            GpuMeshCache.removeIfSame(key, mesh);
            return null;
        }
        if (IVRConfig.gpuOptimization) try {
            GpuMeshCache.publish(key, mesh);
            RenderDebugStats.recordGpuMeshReady();
        } catch (RuntimeException ignored) {
            // CPU replay remains valid when the current version has no usable GL context.
        }
        if (expectedGeneration != generation || !isCurrentKey(key)) {
            CACHE.removeIfSame(key, mesh);
            GpuMeshCache.removeIfSame(key, mesh);
            return null;
        }
        return mesh;
    }

    public static void removeIfCurrent(VertexMeshKey key, VertexMesh mesh,
                                       long expectedGeneration) {
        checkThread();
        if (mesh == null || expectedGeneration != generation) {
            return;
        }
        CACHE.removeIfSame(key, mesh);
        GpuMeshCache.removeIfSame(key, mesh);
    }

    /** Removes only this mesh identity, including after its generation was invalidated. */
    public static void removeIfSame(VertexMeshKey key, VertexMesh mesh) {
        checkThread();
        if (mesh == null) {
            return;
        }
        CACHE.removeIfSame(key, mesh);
        GpuMeshCache.removeIfSame(key, mesh);
    }
    public static VertexMesh requestQuads(VertexMeshKey key, List<QuadSpec> specs) {
        checkThread();
        if (!preparationEnabled()) return null;
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh == null && !reloading && shouldAttemptBuild(key)) {
            if (!BUILDER.requestQuads(new MeshBuildScheduler.Ticket(key, generation, key.revision()), specs)) {
                deferRetry(key);
            } else {
                RETRY_AFTER.remove(key);
            }
        }
        return mesh;
    }
    /** 有界队列已满时，避免每帧重复构建录制器快照。 */
    public static boolean shouldAttemptBuild(VertexMeshKey key) {
        checkThread();
        if (!preparationEnabled()) return false;
        if (!CacheAdmission.shouldAttempt(key)) return false;
        Long retryAfter = RETRY_AFTER.get(key);
        boolean allowed = retryAfter == null || System.nanoTime() >= retryAfter;
        if (allowed) CacheAdmission.recordAttempt(key);
        return allowed;
    }

    /**
     * Cheap CPU-only admission before a cache lookup or capture. GPU preparation
     * keeps the existing key admission path until its own counters are measured.
     */
    public static boolean shouldAttemptCpuCache(VertexMeshKey key) {
        checkThread();
        return IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization
                && preparationEnabled() && CacheAdmission.shouldAttempt(key);
    }

    public static boolean shouldAttemptCpuScope(Class<?> callbackType) {
        checkThread();
        return IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization
                && preparationEnabled() && CacheAdmission.shouldAttemptScope(callbackType);
    }

    public static void recordCpuScopeHit(Class<?> callbackType) {
        checkThread();
        CacheAdmission.recordScopeHit(callbackType);
    }

    public static void recordCpuScopeMiss(Class<?> callbackType) {
        checkThread();
        CacheAdmission.recordScopeMiss(callbackType);
    }
    public static boolean isPending(VertexMeshKey key) {
        checkThread();
        if (!preparationEnabled()) return false;
        return isCurrentKey(key) && BUILDER.isPending(key);
    }
    public static boolean isReloading() { checkThread(); return reloading; }
    public static void queueReloadStart() {
        Minecraft.getInstance().execute(() -> {
            invalidate();
            reloads++;
            reloading = true;
        });
    }
    public static void queueReloadEnd() {
        Minecraft.getInstance().execute(() -> {
            reloads = Math.max(0, reloads - 1);
            reloading = reloads != 0;
        });
    }
    /** 使条目失效；旧的在途任务不能发布到新条目中。 */
    public static void invalidate(VertexMeshKey key) {
        checkThread();
        CACHE.remove(key);
        RETRY_AFTER.remove(key);
        CacheAdmission.invalidate(key);
        GpuMeshCache.remove(key);
        BUILDER.invalidate(key);
    }
    public static void invalidate() {
        checkThread();
        GpuInstanceBatch.invalidate();
        // 释放驻留 GL 对象前清空排队提交。需要排队执行释放时，后端也会立即解除绑定。
        GpuMeshBackend.dispose();
        generation++;
        CACHE.clear();
        RETRY_AFTER.clear();
        CacheAdmission.clear();
        GpuMeshCache.clear();
        EyeCandyMeshCache.clear();
        TrainCpuMeshCache.clear();
        BUILDER.clear();
        TrainBoundsProvider.getInstance().clear();
        RenderCallbackGeometryCache.clear();
        RenderDataRevision.bump();
    }
    /** 每帧最多处理八个完成项或一毫秒；不会等待工作线程。 */
    public static void drain() {
        checkThread();
        if (!preparationEnabled()) return;
        if (reloading) return;
        long started = System.nanoTime();
        for (int count = 0; count < 8 && System.nanoTime() - started < 1_000_000L; count++) {
            MeshBuildScheduler.Completed result = BUILDER.poll();
            if (result == null) break;
            if (result.mesh() != null && isCurrentTicket(result.ticket())) {
                CACHE.put(result.ticket().key(), result.mesh());
                RETRY_AFTER.remove(result.ticket().key());
                if (IVRConfig.gpuOptimization) try {
                    GpuMeshCache.publish(result.ticket().key(), result.mesh());
                    RenderDebugStats.recordGpuMeshReady();
                } catch (RuntimeException ignored) {
                    // CPU 缓存仍可发布；GPU 接管会由常规类型化刷新路径重试。
                }
            } else if (result.mesh() == null && isCurrentTicket(result.ticket())) {
                // 构建失败后必须像队列拒绝一样进入有界退避，否则每个可见回调都会每帧重试，
                // 再次产生此缓存原本要消除的卡顿。
                deferRetry(result.ticket().key());
            }
        }
        GpuMeshCache.drain();
    }

    /** 拒绝工作线程运行期间生命周期版本已变化的结果。 */
    private static boolean isCurrentKey(VertexMeshKey key) {
        return key != null && key.revision() == RenderDataRevision.current();
    }

    private static boolean preparationEnabled() {
        return IVRConfig.cpuOptimization || IVRConfig.gpuOptimization;
    }

    private static boolean isCurrentTicket(MeshBuildScheduler.Ticket ticket) {
        return ticket != null && ticket.generation() == generation
                && ticket.entryGeneration() == ticket.key().revision()
                && isCurrentKey(ticket.key());
    }
}
