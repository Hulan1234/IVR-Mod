package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Records immutable geometry emitted by MTR's scheduled render callbacks.
 * The callback itself remains the fallback path.  A miss records once with an
 * identity pose, queues the pure data copy, and replays that copy for the
 * current frame.  While the worker is building, the caller executes the
 * native callback once per frame instead of recording another copy.  This is
 * deliberately CPU only; version adapters may publish the mesh at a typed
 * render flush when their layer contract is known.
 */
public final class RenderCallbackGeometryCache {
    private static final int MAX_METADATA = 512;
    private static final Map<VertexMeshKey, Metadata> METADATA =
            new LinkedHashMap<>(32, .75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<VertexMeshKey, Metadata> eldest) {
                    return size() > MAX_METADATA;
                }
            };
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

    /** Entry point used by the MSD RenderTrains mixin for Catenary callbacks. */
    public static void renderCatenary(BiConsumer<Object, Object> callback,
                                      PoseStack matrices, VertexConsumer consumer) {
        renderInternal(callback, matrices, consumer, "msd-catenary");
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
        if (DISABLED) {
            callback.accept(matrices, consumer);
            return;
        }
        VertexMeshKey key = key(callback, namespace);
        if (key == null) {
            callback.accept(matrices, consumer);
            return;
        }
        boolean outputAttempted = false;
        try {
            VertexMesh cached = RenderCacheLifecycle.get(key);
            Metadata metadata;
            synchronized (METADATA) {
                metadata = METADATA.get(key);
            }
            if (cached != null && metadata != null) {
                // submitGpuOrReplay returns true when the instance was queued
                // or when submitOrFallback already replayed the CPU mesh.
                // Only an unsupported/missing context returns false and needs
                // the caller's single direct replay.
                if (!submitGpuOrReplay(key, cached, matrices, consumer, metadata.light, metadata.overlay)) {
                    cached.replay(matrices, consumer, metadata.light, metadata.overlay);
                }
                return;
            }

            // Do not invoke the recorder again while the worker owns its copy.
            if (RenderCacheLifecycle.isPending(key)
                    || !RenderCacheLifecycle.shouldAttemptBuild(key)) {
                callback.accept(matrices, consumer);
                return;
            }

            VertexRecorder recorder = new VertexRecorder();
            callback.accept(new PoseStack(), recorder);
            java.util.List<VertexMesh.Vertex> vertices = recorder.snapshot();
            if (vertices.isEmpty() || !recorder.hasLight()) {
                callback.accept(matrices, consumer);
                return;
            }

            // The worker owns VertexMesh construction.  The first frame uses
            // the recorder snapshot directly, avoiding a synchronous mesh
            // allocation and avoiding a second callback invocation.
            RenderCacheLifecycle.request(key, vertices);
            outputAttempted = true;
            recorder.replay(matrices, consumer, recorder.light(), recorder.overlay());
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
    }

    private static VertexMeshKey key(BiConsumer<?, ?> callback, String namespace) {
        StringBuilder signature = new StringBuilder(callback.getClass().getName());
        Field[] fields = callback.getClass().getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        for (Field field : fields) {
            if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(callback);
                if (value instanceof PoseStack || value instanceof VertexConsumer
                        || value instanceof BiConsumer) continue;
                signature.append("|").append(field.getName()).append("=").append(stableValue(value));
            } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                // A callback with inaccessible captures is not safe to cache:
                // its geometry key cannot be proven stable.
                return null;
            }
        }
        LayerContext context = LAYER_CONTEXT.get();
        String texture = context == null ? "" : stableValue(context.texture());
        String material = context == null ? namespace : stableValue(context.layer());
        signature.append("|texture=").append(texture).append("|material=").append(material);
        int variant = signature.toString().hashCode();
        return new VertexMeshKey(namespace + ":" + signature, variant,
                RenderDataRevision.current(), texture, material, 0);
    }

    /**
     * A non-null context means submitOrFallback has already emitted either a
     * queued GPU instance or an exact CPU replay.  A missing context keeps the
     * normal CPU replay path and is used by versions whose lambda descriptor
     * differs from the checked contract.
     */
    private static boolean submitGpuOrReplay(VertexMeshKey key, VertexMesh mesh,
                                             PoseStack matrices, VertexConsumer consumer,
                                             int light, int overlay) {
        LayerContext context = LAYER_CONTEXT.get();
        // These namespaces currently have no version-safe GPU consumer. Keep
        // their immutable mesh on the CPU replay path until a typed VBO owner
        // is installed; publishing a mesh must not accidentally route it to
        // the door instance backend.
        if (key.model().startsWith("msd-catenary:") || key.model().startsWith("nte-rail:")) {
            return false;
        }
        if (context == null || context.layer() == null || context.texture() == null) return false;
        try {
            // submitOrFallback invokes the supplied CPU replay itself when the
            // layer is translucent, unsupported, or GPU initialization fails.
            GpuInstanceBatch.submitOrFallback(context.layer(), context.texture(), key,
                    mesh, matrices, light, overlay, consumer);
            // submitOrFallback owns the CPU replay when the backend rejects
            // the group, so both a successful GPU queue and a CPU fallback are
            // considered handled by this method.
            return true;
        } catch (RuntimeException failure) {
            // A context failure before queue ownership must keep the callback
            // visible.  The caller performs one exact CPU replay below.
            return false;
        }
    }

    private static String stableValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?>) return String.valueOf(value);
        if (value.getClass().isArray()) {
            if (value instanceof Object[] objects) return Arrays.deepToString(objects);
            if (value instanceof int[] ints) return Arrays.toString(ints);
            if (value instanceof long[] longs) return Arrays.toString(longs);
            if (value instanceof double[] doubles) return Arrays.toString(doubles);
            if (value instanceof float[] floats) return Arrays.toString(floats);
            if (value instanceof boolean[] booleans) return Arrays.toString(booleans);
            if (value instanceof byte[] bytes) return Arrays.toString(bytes);
            if (value instanceof short[] shorts) return Arrays.toString(shorts);
            if (value instanceof char[] chars) return Arrays.toString(chars);
        }
        String text = String.valueOf(value);
        return value.getClass().getName() + ':' + text;
    }

    private record Metadata(int light, int overlay) { }
    private record LayerContext(Object layer, Object texture) { }
}
