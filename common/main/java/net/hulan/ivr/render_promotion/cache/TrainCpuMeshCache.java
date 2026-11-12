package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.model.ModelTrainBase;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Records and replays closed-door train body stages in model-local space.
 * Mesh ownership stays with {@link RenderCacheLifecycle}; this class only
 * supplies train-specific keys and the render-thread recorder fallback.
 */
public final class TrainCpuMeshCache {
    private static final ThreadLocal<RenderContext> CONTEXT = new ThreadLocal<>();

    private TrainCpuMeshCache() { }

    @FunctionalInterface
    public interface StageRenderer {
        void render(PoseStack matrices, VertexConsumer consumer);
    }

    /** Supplies the enclosing model render's texture and livery identity. */
    public static void beginRender(ResourceLocation texture, Object livery) {
        CONTEXT.set(new RenderContext(signature(texture), signature(livery)));
    }

    /** Clears the enclosing model render context. */
    public static void endRender() {
        CONTEXT.remove();
    }

    /** Clears a context left by an interrupted render or lifecycle transition. */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Renders one model stage. Door animation and light stages stay on the
     * native path because their vertices are dynamic. The current pose, light,
     * and overlay are always supplied to replay, so only local vertex data is
     * cached.
     */
    public static void renderStage(ModelTrainBase model, PoseStack matrices,
                                   VertexConsumer consumer, ModelTrainBase.RenderStage stage,
                                   int light, int overlay, int carIndex, int trainCars,
                                   boolean reversed, boolean isOnRoute,
                                   float leftDoorX, float rightDoorX,
                                   float leftDoorZ, float rightDoorZ,
                                   StageRenderer renderer) {
        if (!IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization) {
            if (renderer != null && matrices != null && consumer != null) {
                renderer.render(matrices, consumer);
            }
            return;
        }
        if (renderer == null || matrices == null || consumer == null || model == null
                || !isCacheable(stage, leftDoorX, rightDoorX, leftDoorZ, rightDoorZ)
                || CONTEXT.get() == null) {
            if (renderer != null && matrices != null && consumer != null) {
                renderer.render(matrices, consumer);
            }
            return;
        }

        VertexMeshKey key = key(model, stage, carIndex, trainCars, reversed, isOnRoute);
        if (IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization
                && !RenderCacheLifecycle.shouldAttemptCpuCache(key)) {
            renderer.render(matrices, consumer);
            return;
        }
        boolean outputAttempted = false;
        try {
            VertexMesh cached = RenderCacheLifecycle.getPrepared(key);
            if (cached != null) {
                if (IVRConfig.cpuOptimization) {
                    outputAttempted = true;
                    cached.replay(matrices, consumer, light, overlay);
                } else {
                    // GPU preparation stays alive while CPU replay is disabled;
                    // this stage has no typed GPU layer contract, so preserve
                    // the native renderer until one is available.
                    renderer.render(matrices, consumer);
                }
                return;
            }

            // A pending or throttled ticket owns the next recording attempt;
            // keep this frame on MTR's native path.
            if (RenderCacheLifecycle.isPending(key)
                    || !RenderCacheLifecycle.shouldAttemptBuild(key)) {
                renderer.render(matrices, consumer);
                return;
            }

            // ModelTrainBase mutates the supplied pose stack. Record with a
            // fresh identity stack, then replay through the caller's live pose.
            VertexRecorder recorder = new VertexRecorder();
            renderer.render(new PoseStack(), recorder);
            List<VertexMesh.Vertex> vertices = recorder.snapshot();
            if (vertices.isEmpty()) {
                renderer.render(matrices, consumer);
                return;
            }

            RenderCacheLifecycle.request(key, vertices);
            outputAttempted = true;
            recorder.replay(matrices, consumer, light, overlay);
        } catch (RuntimeException cacheFailure) {
            // Caching is optional. Never duplicate a partially replayed stage.
            if (!outputAttempted) {
                renderer.render(matrices, consumer);
            }
        }
    }

    private static boolean isCacheable(ModelTrainBase.RenderStage stage,
                                       float leftDoorX, float rightDoorX,
                                       float leftDoorZ, float rightDoorZ) {
        return stage != null
                && stage != ModelTrainBase.RenderStage.LIGHTS
                && stage != ModelTrainBase.RenderStage.ALWAYS_ON_LIGHTS
                && leftDoorX == 0.0F && rightDoorX == 0.0F
                && leftDoorZ == 0.0F && rightDoorZ == 0.0F;
    }

    private static VertexMeshKey key(ModelTrainBase model, ModelTrainBase.RenderStage stage,
                                     int carIndex, int trainCars, boolean reversed,
                                     boolean isOnRoute) {
        RenderContext context = CONTEXT.get();
        String identity = "train:" + model.getClass().getName()
                + '@' + Integer.toUnsignedString(System.identityHashCode(model))
                + "|stage=" + stage
                + "|car=" + carIndex
                + "|cars=" + trainCars
                + "|reversed=" + reversed
                + "|route=" + isOnRoute;
        return new VertexMeshKey(identity, identity.hashCode(), RenderDataRevision.current(),
                0L, context.texture(), context.livery(), 0);
    }

    private static String signature(Object value) {
        if (value == null) return "none";
        String text = String.valueOf(value);
        if (text.length() > 512) text = text.substring(0, 512);
        return value.getClass().getName() + '@'
                + Integer.toUnsignedString(System.identityHashCode(value)) + ':' + text;
    }

    private record RenderContext(String texture, String livery) { }
}
