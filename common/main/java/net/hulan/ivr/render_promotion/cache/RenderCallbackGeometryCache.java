package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.TrainClient;
import mtr.model.ModelTrainBase;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Records immutable geometry emitted by MTR's scheduled render callbacks.
 * The callback itself remains the fallback path.  A miss records once with an
 * identity pose, queues the pure data copy, and replays that copy for the
 * current frame.  While the worker is building, the caller executes the
 * native callback once per frame instead of recording another copy.  This is
 * CPU replay remains the fallback; a typed version adapter may consume a
 * published mesh at render flush when its layer contract is known.
 */
public final class RenderCallbackGeometryCache {
    private static final int MAX_METADATA = 512;
    private static final int MAX_STABLE_CHARS = 16 * 1024;
    private static final int MAX_STABLE_ENTRIES = 256;
    private static final Map<VertexMeshKey, Metadata> METADATA =
            new LinkedHashMap<>(32, .75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<VertexMeshKey, Metadata> eldest) {
                    return size() > MAX_METADATA;
                }
            };
    private static final int MAX_CAPTURE_PLANS = 256;
    /** Callback classes are generated once per call-site; cache their field plan. */
    private static final Map<Class<?>, CapturePlan> CAPTURE_PLANS =
            new LinkedHashMap<>(32, .75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Class<?>, CapturePlan> eldest) {
                    return size() > MAX_CAPTURE_PLANS;
                }
            };
    private static final int MAX_KEY_CACHE = 512;
    private static final Map<BiConsumer<?, ?>, CachedKey> KEY_CACHE =
            new IdentityHashMap<>();
    private static volatile boolean DISABLED;
    /** The enclosing RenderTrains layer supplies the exact RenderType and texture. */
    private static final ThreadLocal<LayerContext> LAYER_CONTEXT = new ThreadLocal<>();

    private RenderCallbackGeometryCache() { }

    /** Disable this optional cache after a runtime Mixin contract mismatch. */
    public static void disable(String reason) {
        DISABLED = true;
        clear();
    }

    public static void render(BiConsumer<Object, Object> callback,
                              PoseStack matrices, VertexConsumer consumer) {
        renderInternal(callback, matrices, consumer, "mtr-callback");
    }

    /** 使用 MSD 接触网独立命名空间记录回调网格。 */
    public static void renderCatenary(BiConsumer<Object, Object> callback,
                                      PoseStack matrices, VertexConsumer consumer) {
        renderInternal(callback, matrices, consumer, "msd-catenary");
    }

    /** 站牌回调保留原生渲染，避免缓存遗漏其动态矩阵变换。 */
    public static boolean isYamanoteDisplayCallback(BiConsumer<?, ?> callback) {
        return callback != null && callback.getClass().getName().contains("RenderYamanoteRailwaySign");
    }

    /** Begin a callback batch.  The texture is known before the buffer is selected. */
    public static void beginLayer(Object texture) {
        LAYER_CONTEXT.set(new LayerContext(null, texture));
    }

    /** Records the exact buffer layer selected by MTR for the current callback batch. */
    public static void setLayer(Object layer) {
        LayerContext context = LAYER_CONTEXT.get();
        if (context == null) {
            LAYER_CONTEXT.set(new LayerContext(layer, null));
        } else {
            LAYER_CONTEXT.set(new LayerContext(layer, context.texture()));
        }
    }

    /** Always clear the context at the end of the enclosing RenderTrains lambda. */
    public static void endLayer() {
        LAYER_CONTEXT.remove();
    }

    private static void renderInternal(BiConsumer<Object, Object> callback,
                                       PoseStack matrices, VertexConsumer consumer,
                                       String namespace) {
        if (callback == null || matrices == null || consumer == null) return;
        if (!IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization) {
            callback.accept(matrices, consumer);
            return;
        }
        if (isTrainGeometryCallback(callback)) {
            callback.accept(matrices, consumer);
            return;
        }
        boolean cpuOnly = IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization;
        Class<?> callbackType = callback.getClass();
        if (cpuOnly && !RenderCacheLifecycle.shouldAttemptCpuScope(callbackType)) {
            callback.accept(matrices, consumer);
            return;
        }
        if (DISABLED) {
            callback.accept(matrices, consumer);
            return;
        }
        long keyStarted = RenderDebugStats.startCpuTiming();
        VertexMeshKey key = key(callback, namespace);
        RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.KEY, keyStarted);
        if (key == null) {
            callback.accept(matrices, consumer);
            return;
        }
        if (cpuOnly && !RenderCacheLifecycle.shouldAttemptCpuCache(key)) {
            callback.accept(matrices, consumer);
            return;
        }
        boolean outputAttempted = false;
        try {
            long stabilityStarted = RenderDebugStats.startCpuTiming();
            VertexMesh cached = RenderCacheLifecycle.getPrepared(key);
            if (cpuOnly) {
                if (cached == null) RenderCacheLifecycle.recordCpuScopeMiss(callbackType);
                else RenderCacheLifecycle.recordCpuScopeHit(callbackType);
            }
            Metadata metadata;
            synchronized (METADATA) {
                metadata = METADATA.get(key);
            }
            RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.STABILITY, stabilityStarted);
            if (cached != null && metadata != null) {
                // Only a submission that never reached either consumer needs
                // the caller's single direct replay. CPU fallback and uncertain
                // ownership are already handled by the batch owner.
                GpuInstanceBatch.ConsumptionStatus status = IVRConfig.gpuOptimization
                        ? submitGpuOrReplay(key, cached, matrices, consumer, metadata.light, metadata.overlay)
                        : GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED;
                if (status == GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED) {
                    if (IVRConfig.cpuOptimization) {
                        outputAttempted = true;
                        long replayStarted = RenderDebugStats.startCpuTiming();
                        cached.replay(matrices, consumer, metadata.light, metadata.overlay);
                        RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.REPLAY, replayStarted);
                    } else {
                        callback.accept(matrices, consumer);
                    }
                }
                return;
            }

            // Do not invoke the recorder again while the worker owns its copy.
            long admissionStarted = RenderDebugStats.startCpuTiming();
            boolean pending = RenderCacheLifecycle.isPending(key);
            boolean shouldAttempt = !pending && RenderCacheLifecycle.shouldAttemptBuild(key);
            RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.STABILITY, admissionStarted);
            if (pending || !shouldAttempt) {
                callback.accept(matrices, consumer);
                return;
            }

            VertexRecorder recorder = new VertexRecorder();
            long captureStarted = RenderDebugStats.startCpuTiming();
            callback.accept(new PoseStack(), recorder);
            java.util.List<VertexMesh.Vertex> vertices = recorder.snapshot();
            RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.CAPTURE, captureStarted);
            if (vertices.isEmpty() || !recorder.hasLight()) {
                callback.accept(matrices, consumer);
                return;
            }

            // The worker owns VertexMesh construction.  The first frame uses
            // the recorder snapshot directly, avoiding a synchronous mesh
            // allocation and avoiding a second callback invocation.
            RenderCacheLifecycle.request(key, vertices);
            outputAttempted = true;
            long replayStarted = RenderDebugStats.startCpuTiming();
            recorder.replay(matrices, consumer, recorder.light(), recorder.overlay());
            RenderDebugStats.recordCpuPhase(RenderDebugStats.CpuPhase.REPLAY, replayStarted);
            synchronized (METADATA) {
                METADATA.put(key, new Metadata(recorder.light(), recorder.overlay()));
            }
        } catch (RuntimeException failure) {
            // Once replay started, never invoke the native callback a second
            // time because that would duplicate a partially submitted stage.
            if (!outputAttempted) callback.accept(matrices, consumer);
        }
    }

    public static void clear() {
        synchronized (METADATA) {
            METADATA.clear();
        }
        synchronized (KEY_CACHE) {
            KEY_CACHE.clear();
        }
    }

    private static VertexMeshKey key(BiConsumer<?, ?> callback, String namespace) {
        final CapturePlan plan;
        try {
            plan = capturePlan(callback.getClass());
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
        if (!plan.safe()) return null;
        try {
            long revision = RenderDataRevision.current();
            LayerContext context = LAYER_CONTEXT.get();
            Object layer = context == null ? null : context.layer();
            Object textureObject = context == null ? null : context.texture();
            if (plan.immutable() && !plan.hasBlockEntity()) {
                synchronized (KEY_CACHE) {
                    CachedKey cached = KEY_CACHE.get(callback);
                    if (cached != null && cached.revision() == revision
                            && cached.layer() == layer && cached.texture() == textureObject
                            && cached.namespace().equals(namespace)) {
                        return cached.key();
                    }
                }
            }
            long fingerprint = 0xcbf29ce484222325L;
            for (CaptureField capture : plan.fields()) {
                if (capture.kind() == FieldKind.SKIP) continue;
                Object value = capture.field().get(callback);
                long valueFingerprint = captureFingerprint(capture.kind(), value);
                if (valueFingerprint == Long.MIN_VALUE) return null;
                fingerprint ^= capture.field().getName().hashCode();
                fingerprint *= 0x100000001b3L;
                fingerprint ^= valueFingerprint;
                fingerprint *= 0x100000001b3L;
            }
            String texture = cheapContextValue(textureObject);
            String material = context == null ? namespace : cheapContextValue(layer);
            String model = namespace + ":" + callback.getClass().getName() + "#"
                    + Long.toUnsignedString(fingerprint, 16);
            VertexMeshKey result = new VertexMeshKey(model, model.hashCode(), revision, texture, material, 0);
            if (plan.immutable() && !plan.hasBlockEntity()) {
                synchronized (KEY_CACHE) {
                    KEY_CACHE.put(callback, new CachedKey(namespace, revision, layer, textureObject, result));
                    while (KEY_CACHE.size() > MAX_KEY_CACHE) {
                        KEY_CACHE.remove(KEY_CACHE.keySet().iterator().next());
                    }
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            // Unknown, inaccessible, or mutable captures must stay on the
            // native path. An identity token would alias changed geometry.
            return null;
        }
    }

    private static CapturePlan capturePlan(Class<?> type) {
        synchronized (CAPTURE_PLANS) {
            CapturePlan cached = CAPTURE_PLANS.get(type);
            if (cached != null) return cached;
            Field[] declared = type.getDeclaredFields();
            Arrays.sort(declared, Comparator.comparing(Field::getName));
            List<CaptureField> fields = new ArrayList<>(declared.length);
            boolean safe = true;
            for (Field field : declared) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
                FieldKind kind = fieldKind(field.getType());
                if (kind == FieldKind.UNSAFE) {
                    safe = false;
                    break;
                }
                try {
                    field.setAccessible(true);
                } catch (RuntimeException | LinkageError inaccessible) {
                    safe = false;
                    break;
                }
                fields.add(new CaptureField(field, kind));
            }
            boolean immutable = safe && fields.stream().allMatch(capture ->
                    java.lang.reflect.Modifier.isFinal(capture.field().getModifiers()));
            boolean hasBlockEntity = fields.stream().anyMatch(capture -> capture.kind() == FieldKind.BLOCK_ENTITY);
            CapturePlan result = new CapturePlan(safe, safe ? List.copyOf(fields) : List.of(), immutable, hasBlockEntity);
            CAPTURE_PLANS.put(type, result);
            return result;
        }
    }

    private static FieldKind fieldKind(Class<?> type) {
        if (PoseStack.class.isAssignableFrom(type)
                || VertexConsumer.class.isAssignableFrom(type)) return FieldKind.SKIP;
        if (type.isPrimitive() || type == String.class || type == Boolean.class
                || type == Character.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class
                || type == Double.class || type.isEnum() || type == ResourceLocation.class
                || type == BlockPos.class) return FieldKind.CHEAP;
        if (ModelTrainBase.class.isAssignableFrom(type) || TrainClient.class.isAssignableFrom(type)) {
            // 暂停列车几何缓存；捕获列车模型或数据的回调保留 MTR 原生姿态与绘制路径。
            return FieldKind.UNSAFE;
        }
        if (BlockEntity.class.isAssignableFrom(type)) return FieldKind.BLOCK_ENTITY;
        return FieldKind.UNSAFE;
    }

    /** 列车几何保留 MTR 原生逐帧变换与绘制路径。 */
    private static boolean isTrainGeometryCallback(BiConsumer<?, ?> callback) {
        try {
            for (Class<?> type = callback.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if (name.contains("mtr.model.ModelTrainBase") || name.contains("mtr.data.TrainClient")) {
                    return true;
                }
                for (Field field : type.getDeclaredFields()) {
                    Class<?> capturedType = field.getType();
                    if (ModelTrainBase.class.isAssignableFrom(capturedType)
                            || TrainClient.class.isAssignableFrom(capturedType)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return true;
        }
        return false;
    }

    private static String encodeCapture(FieldKind kind, Object value) {
        if (kind == FieldKind.SKIP) return "";
        if (kind == FieldKind.BLOCK_ENTITY && value instanceof BlockEntity entity) {
            return "BlockEntity:" + entity.getClass().getName() + ':'
                    + entity.getBlockPos().asLong() + ':' + RenderDataRevision.instance(entity);
        }
        if (kind == FieldKind.CHEAP || kind == FieldKind.STABLE_VALUE) {
            return stableValue(value);
        }
        return null;
    }

    /** Fast fingerprint for the supported immutable callback capture types. */
    private static long captureFingerprint(FieldKind kind, Object value) {
        if (kind == FieldKind.SKIP) return 0L;
        if (value == null) return 0x9e3779b97f4a7c15L;
        if (kind == FieldKind.BLOCK_ENTITY && value instanceof BlockEntity entity) {
            long result = System.identityHashCode(entity);
            result = result * 31L + entity.getBlockPos().asLong();
            return result * 31L + RenderDataRevision.instance(entity);
        }
        if (kind != FieldKind.CHEAP && kind != FieldKind.STABLE_VALUE) return Long.MIN_VALUE;
        if (value instanceof Number number) return Double.doubleToLongBits(number.doubleValue());
        if (value instanceof Boolean bool) return bool ? 1L : 0L;
        if (value instanceof Character character) return character.charValue();
        if (value instanceof BlockPos pos) return pos.asLong();
        if (value instanceof ResourceLocation resource) return resource.toString().hashCode();
        if (value instanceof Enum<?> enumeration) return enumeration.ordinal() * 31L + enumeration.getClass().getName().hashCode();
        if (value instanceof String string) return string.hashCode();
        return Long.MIN_VALUE;
    }

    private static String cheapContextValue(Object value) {
        if (value == null) return "";
        if (value instanceof ResourceLocation resource) return resource.toString();
        return value.getClass().getName() + '@' + Integer.toUnsignedString(System.identityHashCode(value));
    }

    /**
     * A non-null context means submitOrFallback has already emitted either a
     * queued GPU instance or an exact CPU replay.  A missing context keeps the
     * normal CPU replay path and is used by versions whose lambda descriptor
     * differs from the checked contract.
     */
    private static GpuInstanceBatch.ConsumptionStatus submitGpuOrReplay(VertexMeshKey key, VertexMesh mesh,
                                                                         PoseStack matrices, VertexConsumer consumer,
                                                                         int light, int overlay) {
        LayerContext context = LAYER_CONTEXT.get();
        // ANTE rail callbacks still have no typed GPU consumer. MSD catenary
        // callbacks may use the shared instance backend when the selected
        // layer and version adapter prove compatible; every failure remains a
        // direct CPU replay through the status returned below.
        if (key.model().startsWith("nte-rail:")) {
            return GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED;
        }
        if (context == null || context.layer() == null || context.texture() == null) {
            return GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED;
        }
        try {
            return GpuInstanceBatch.submitOrFallbackStatus(context.layer(), context.texture(), key,
                    mesh, matrices, light, overlay, consumer);
        } catch (RuntimeException | LinkageError failure) {
            // A thrown submission has not transferred ownership to the batch queue.
            GpuInstanceBatch.disableBackend();
            return GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED;
        }
    }

    private static String stableValue(Object value) {
        String result = stableValue(value, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        return result.length() <= MAX_STABLE_CHARS
                ? result
                : rejectOversize(value);
    }

    /** Serialize only known pure-data captures; unknown objects stay identity-keyed. */
    private static String stableValue(Object value, Set<Object> seen, int depth) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?> || value instanceof ResourceLocation) {
            return value.getClass().getName() + ':' + String.valueOf(value);
        }
        if (value instanceof BlockPos pos) return "BlockPos:" + pos.asLong();
        if (value instanceof BlockEntity entity) {
            return "BlockEntity:" + entity.getClass().getName() + ':' + entity.getBlockPos().asLong()
                    + ':' + RenderDataRevision.instance(entity);
        }
        Class<?> valueType = value.getClass();
        if (value instanceof ModelTrainBase || value instanceof TrainClient
                || valueType.getName().equals("mtr.render.StoredMatrixTransformations")) {
            throw new IllegalArgumentException("Mutable render state cannot be used in a callback cache key: "
                    + valueType.getName());
        }
        if (depth > 6 || !seen.add(value)) {
            throw new IllegalArgumentException("Unstable callback capture cannot be used in a cache key: "
                    + valueType.getName());
        }
        try {
            if (value instanceof Map<?, ?> map) {
                List<String> entries = new java.util.ArrayList<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (++count > MAX_STABLE_ENTRIES) return rejectOversize(value);
                    entries.add(stableValue(entry.getKey(), seen, depth + 1) + "="
                            + stableValue(entry.getValue(), seen, depth + 1));
                }
                entries.sort(String::compareTo);
                String result = value.getClass().getName() + entries;
                return result.length() <= MAX_STABLE_CHARS ? result : rejectOversize(value);
            }
            if (value instanceof Collection<?> collection) {
                List<String> entries = new java.util.ArrayList<>();
                int count = 0;
                for (Object entry : collection) {
                    if (++count > MAX_STABLE_ENTRIES) return rejectOversize(value);
                    entries.add(stableValue(entry, seen, depth + 1));
                }
                if (value instanceof java.util.Set<?>) entries.sort(String::compareTo);
                StringBuilder result = new StringBuilder(value.getClass().getName()).append('[');
                for (String entry : entries) {
                    if (result.length() + entry.length() + 1 > MAX_STABLE_CHARS) return rejectOversize(value);
                    result.append(entry).append(';');
                }
                result.append(']');
                return result.length() <= MAX_STABLE_CHARS ? result.toString() : rejectOversize(value);
            }
            Class<?> type = valueType;
            if (type.isArray()) {
                StringBuilder result = new StringBuilder(type.getName()).append('[');
                int length = java.lang.reflect.Array.getLength(value);
                if (length > MAX_STABLE_ENTRIES) return rejectOversize(value);
                for (int index = 0; index < length; index++) {
                    if (result.length() >= MAX_STABLE_CHARS) return rejectOversize(value);
                    result.append(stableValue(java.lang.reflect.Array.get(value, index), seen, depth + 1)).append(';');
                }
                result.append(']');
                return result.length() <= MAX_STABLE_CHARS ? result.toString() : rejectOversize(value);
            }
            // MTR's transformation holder and compiler-generated lambdas contain
            // only the captured coordinates, direction and block entity state.
            if (type.getName().equals("mtr.render.StoredMatrixTransformations") || type.isSynthetic()) {
                StringBuilder result = new StringBuilder(type.getName()).append('{');
                Field[] fields = type.getDeclaredFields();
                Arrays.sort(fields, Comparator.comparing(Field::getName));
                for (Field field : fields) {
                    if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
                    field.setAccessible(true);
                    result.append(field.getName()).append('=')
                            .append(stableValue(field.get(value), seen, depth + 1)).append(';');
                    if (result.length() > MAX_STABLE_CHARS) return rejectOversize(value);
                }
                result.append('}');
                return result.length() <= MAX_STABLE_CHARS ? result.toString() : rejectOversize(value);
            }
            return type.getName() + '@' + Integer.toHexString(System.identityHashCode(value));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            seen.remove(value);
        }
    }

    /** Oversized captures are uncacheable; a shared token could alias distinct values. */
    private static String rejectOversize(Object value) {
        throw new IllegalArgumentException("Oversized callback capture: " + value.getClass().getName());
    }

    private enum FieldKind {
        SKIP, CHEAP, BLOCK_ENTITY, STABLE_VALUE, UNSAFE
    }

    private record CaptureField(Field field, FieldKind kind) { }
    private record CapturePlan(boolean safe, List<CaptureField> fields, boolean immutable, boolean hasBlockEntity) { }
    private record CachedKey(String namespace, long revision, Object layer, Object texture, VertexMeshKey key) { }
    private record Metadata(int light, int overlay) { }
    private record LayerContext(Object layer, Object texture) { }
}
