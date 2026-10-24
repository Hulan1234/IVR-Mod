package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshCache;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.cache.TrainGeometryCache;
import net.hulan.ivr.render_promotion.cache.RenderCallbackGeometryCache;
import net.hulan.ivr.render_promotion.cache.NTERailChunkCache;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Client-thread lifecycle and bounded result publication; this CPU cache has no GPU uploads. */
public final class RenderCacheLifecycle {
    private static final VertexMeshCache CACHE = new VertexMeshCache(256, 8L * 1024 * 1024);
    private static final MeshBuildScheduler BUILDER = new MeshBuildScheduler();
    private static Object world;
    private static long generation;
    private static boolean reloading;
    private static int reloads;
    private static final long RETRY_DELAY_NANOS = 250_000_000L;
    private static final Map<VertexMeshKey, Long> RETRY_AFTER = new HashMap<>();

    private RenderCacheLifecycle() {}
    private static void checkThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Client thread required");
    }
    public static void beginFrame(Object currentWorld) {
        checkThread();
        TrainGeometryCache.beginFrame();
        GpuInstanceBatch.beginFrame();
        if (world != currentWorld) {
            invalidate();
            world = currentWorld;
            TrainGeometryCache.clear();
        }
    }
    public static VertexMesh request(VertexMeshKey key, List<VertexMesh.Vertex> vertices) {
        checkThread();
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh == null && !reloading) {
            if (shouldAttemptBuild(key)) {
                if (!BUILDER.request(new MeshBuildScheduler.Ticket(key, generation, key.revision()), vertices)) {
                    RETRY_AFTER.put(key, System.nanoTime() + RETRY_DELAY_NANOS);
                } else {
                    RETRY_AFTER.remove(key);
                }
            }
        }
        return mesh;
    }
    public static VertexMesh get(VertexMeshKey key) {
        checkThread();
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh != null) {
            try {
                GpuMeshCache.publish(key, mesh);
            } catch (RuntimeException ignored) {
                // CPU replay remains valid when a version backend cannot own
                // the mesh or the current GL context is unavailable.
            }
        }
        return mesh;
    }
    public static VertexMesh requestQuads(VertexMeshKey key, List<QuadSpec> specs) {
        checkThread();
        if (!isCurrentKey(key)) return null;
        VertexMesh mesh = CACHE.get(key);
        if (mesh == null && !reloading && shouldAttemptBuild(key)) {
            if (!BUILDER.requestQuads(new MeshBuildScheduler.Ticket(key, generation, key.revision()), specs)) {
                RETRY_AFTER.put(key, System.nanoTime() + RETRY_DELAY_NANOS);
            } else {
                RETRY_AFTER.remove(key);
            }
        }
        return mesh;
    }
    /** Avoid rebuilding a recorder snapshot every frame when the bounded queue is full. */
    public static boolean shouldAttemptBuild(VertexMeshKey key) {
        checkThread();
        Long retryAfter = RETRY_AFTER.get(key);
        return retryAfter == null || System.nanoTime() >= retryAfter;
    }
    public static boolean isPending(VertexMeshKey key) {
        checkThread();
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
    /** Invalidate an entry; an old in-flight ticket cannot publish into the new entry. */
    public static void invalidate(VertexMeshKey key) {
        checkThread();
        CACHE.remove(key);
        RETRY_AFTER.remove(key);
        GpuMeshCache.remove(key);
        BUILDER.invalidate(key);
    }
    public static void invalidate() {
        checkThread();
        GpuInstanceBatch.invalidate();
        // Clear queued submissions before releasing resident GL objects. The
        // backend also detaches immediately when the release must be queued.
        GpuMeshBackend.dispose();
        generation++;
        CACHE.clear();
        RETRY_AFTER.clear();
        GpuMeshCache.clear();
        BUILDER.clear();
        TrainGeometryCache.clear();
        RenderCallbackGeometryCache.clear();
        NTERailChunkCache.clear();
        RenderDataRevision.bump();
    }
    /** At most eight completions or one millisecond per frame; never waits for a worker. */
    public static void drain() {
        checkThread();
        if (reloading) return;
        long started = System.nanoTime();
        for (int count = 0; count < 8 && System.nanoTime() - started < 1_000_000L; count++) {
            MeshBuildScheduler.Completed result = BUILDER.poll();
            if (result == null) break;
            if (result.mesh() != null && isCurrentTicket(result.ticket())) {
                CACHE.put(result.ticket().key(), result.mesh());
                RETRY_AFTER.remove(result.ticket().key());
                try {
                    GpuMeshCache.publish(result.ticket().key(), result.mesh());
                } catch (RuntimeException ignored) {
                    // The CPU cache is still publishable; GPU ownership is
                    // retried by the normal typed flush path.
                }
            } else if (result.mesh() == null && isCurrentTicket(result.ticket())) {
                // A failed build must enter the same bounded backoff as a
                // rejected queue, otherwise every visible callback retries on
                // every frame and recreates the hitch this cache avoids.
                RETRY_AFTER.put(result.ticket().key(),
                        System.nanoTime() + RETRY_DELAY_NANOS);
            }
        }
        GpuMeshCache.drain();
    }

    /** Reject results from a revision that changed while a worker was running. */
    private static boolean isCurrentKey(VertexMeshKey key) {
        return key != null && key.revision() == RenderDataRevision.current();
    }

    private static boolean isCurrentTicket(MeshBuildScheduler.Ticket ticket) {
        return ticket != null && ticket.generation() == generation
                && ticket.entryGeneration() == ticket.key().revision()
                && isCurrentKey(ticket.key());
    }
}
