package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.model.ModelTrainBase;
import mtr.data.TrainClient;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches train model stages in car local coordinates.
 * The model is evaluated once on a cache miss with an identity pose.  The
 * resulting immutable vertices are then replayed through the current pose on
 * every frame.  A moving train therefore changes only the pose supplied by
 * Minecraft; it does not rebuild or translate a CPU vertex array.  This class
 * deliberately does not call OpenGL.  GPU publication is performed by the
 * existing render-thread cache lifecycle, and unsupported layers keep using
 * the normal VertexConsumer path.
 */
public final class TrainGeometryCache {
    private static final int MAX_POSES = 4096;
    private static final long POSE_TTL_FRAMES = 120;
    private static final Logger LOGGER = LogManager.getLogger("ivr");
    private static long poseFrame;
    private static long poseHits;
    private static long poseMisses;
    private static long poseEvictions;
    private static final Map<PoseKey, InstancePose> POSES = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PoseKey, InstancePose> eldest) {
            if (size() > MAX_POSES) {
                poseEvictions++;
                return true;
            }
            return false;
        }
    };
    /** Revision of train-local geometry.  Pose changes do not touch it. */
    private static volatile long geometryRevision;
    private static final Map<TrainClient, Long> TRAIN_SIGNATURES = new WeakHashMap<>();
    private static volatile boolean reflectionUnavailable;
    private static boolean reflectionFailureReported;

    private TrainGeometryCache() {}

    @FunctionalInterface
    public interface StageRenderer {
        void render(PoseStack matrices, VertexConsumer consumer);
    }

    /**
     * Render one MTR model stage exactly once.  A failed recording, an empty
     * model, or a cache lifecycle exception falls back to the original call
     * so a cache miss can never make a train disappear.
     */
    public static void renderStage(ModelTrainBase model, PoseStack matrices,
                                   VertexConsumer consumer, ModelTrainBase.RenderStage stage,
                                   int light, int carIndex, int trainCars, boolean reversed,
                                   boolean isOnRoute, float leftDoorX, float rightDoorX,
                                   float leftDoorZ, float rightDoorZ, StageRenderer renderer) {
        if (renderer == null || matrices == null || consumer == null) {
            return;
        }
        if (model == null || reflectionUnavailable) {
            renderer.render(matrices, consumer);
            return;
        }

        VertexMeshKey key = key(model, stage, carIndex, trainCars, reversed, isOnRoute,
                leftDoorX, rightDoorX, leftDoorZ, rightDoorZ);
        boolean outputAttempted = false;
        try {
            VertexMesh cached = RenderCacheLifecycle.get(key);
            if (cached != null) {
                outputAttempted = true;
                cached.replay(matrices, consumer, light, 0);
                updatePose(model, carIndex, matrices);
                return;
            }

            // The worker owns the immutable copy after request() returns.  Do
            // not record the same stage again while that copy is pending; the
            // original renderer is the only CPU submission for this frame.
            // This removes the per-frame recorder allocation and mesh copy
            // that used to happen during the whole build window.
            if (RenderCacheLifecycle.isPending(key)
                    || !RenderCacheLifecycle.shouldAttemptBuild(key)) {
                renderer.render(matrices, consumer);
                return;
            }

            // The recorder must see model-local coordinates.  The original
            // matrices are kept untouched and are used for this frame's replay.
            VertexRecorder recorder = new VertexRecorder();
            renderer.render(new PoseStack(), recorder);
            List<VertexMesh.Vertex> vertices = recorder.snapshot();
            if (vertices.isEmpty()) {
                outputAttempted = true;
                renderer.render(matrices, consumer);
                return;
            }

            // Construction remains bounded and asynchronous in
            // RenderCacheLifecycle.  Replay the immutable snapshot now so the
            // first frame has the same output as the original renderer.
            RenderCacheLifecycle.request(key, vertices);
            outputAttempted = true;
            new VertexMesh(vertices).replay(matrices, consumer, light, 0);
            updatePose(model, carIndex, matrices);
        } catch (RuntimeException cacheFailure) {
            // Caching is an optimization.  Never let a cache or mapping issue
            // change MTR's rendering behavior or crash the client.
            // Once replay has started, do not invoke the native renderer a
            // second time: that would duplicate a partially submitted stage.
            if (!outputAttempted) renderer.render(matrices, consumer);
        }
    }

    /** Clears geometry and pose data after a resource/world lifecycle change. */
    public static void clear() {
        synchronized (POSES) {
            POSES.clear();
        }
        synchronized (TRAIN_SIGNATURES) {
            TRAIN_SIGNATURES.clear();
        }
    }

    /** Advances the bounded pose cache and removes entries unused for two seconds. */
    public static void beginFrame() {
        synchronized (POSES) {
            poseFrame++;
            if ((poseFrame & 63L) == 0L) prunePoses();
        }
    }

    private static void prunePoses() {
        long cutoff = poseFrame - POSE_TTL_FRAMES;
        Iterator<Map.Entry<PoseKey, InstancePose>> iterator = POSES.entrySet().iterator();
        while (iterator.hasNext()) {
            InstancePose pose = iterator.next().getValue();
            if (pose.lastFrame() < cutoff) {
                iterator.remove();
                poseEvictions++;
            }
        }
    }

    /**
     * A caller that detects a siding/consist topology change can clear the
     * instance pose table.  Local geometry keys already include car count and
     * stage parameters, so a train moving between tracks does not rebuild
     * static vertices unnecessarily.
     */
    public static void markSidingChanged() {
        geometryRevision++;
        clear();
    }

    /** Resource reloads invalidate every local model vertex. */
    public static void markResourceReloaded() {
        geometryRevision++;
        clear();
    }

    /**
     * Observe the real client train state at the position update boundary.
     * The position values themselves are intentionally excluded because a
     * moving train changes only its pose.  A changed car count, spacing,
     * siding/path identity, or route state changes the structure revision.
     */
    public static void observeTrain(TrainClient train, Vec3[] positions) {
        if (train == null) return;
        long signature = 17L;
        signature = 31L * signature + (positions == null ? 0 : positions.length);
        signature = 31L * signature + train.spacing;
        Long siding = reflectiveState(train, "sidingId");
        Long path = reflectiveState(train, "path");
        Long cars = reflectiveState(train, "trainCars");
        if (siding == null || path == null || cars == null) {
            reflectionUnavailable = true;
            if (!reflectionFailureReported) {
                reflectionFailureReported = true;
                LOGGER.warn("IVR train geometry cache disabled: unable to read MTR train structure fields");
            }
            geometryRevision++;
            clear();
            return;
        }
        signature = 31L * signature + siding;
        signature = 31L * signature + path;
        signature = 31L * signature + cars;
        boolean structureChanged;
        synchronized (TRAIN_SIGNATURES) {
            Long previous = TRAIN_SIGNATURES.put(train, signature);
            structureChanged = previous != null && previous != signature;
        }
        if (structureChanged) {
            geometryRevision++;
            synchronized (POSES) {
                POSES.clear();
            }
        }
    }

    /** Snapshot of the latest transform for a model/car pair. */
    public static InstancePose pose(ModelTrainBase model, int carIndex) {
        synchronized (POSES) {
            InstancePose result = POSES.get(new PoseKey(model, carIndex));
            if (result == null) poseMisses++; else poseHits++;
            return result;
        }
    }

    private static void updatePose(ModelTrainBase model, int carIndex, PoseStack matrices) {
        // The matrix is intentionally copied.  PoseStack entries are mutable
        // and are popped by the caller immediately after a car is rendered.
        synchronized (POSES) {
            POSES.put(new PoseKey(model, carIndex), new InstancePose(matrixValues(matrices.last().pose()), poseFrame));
        }
    }

    private static VertexMeshKey key(ModelTrainBase model, ModelTrainBase.RenderStage stage,
                                     int carIndex, int trainCars, boolean reversed, boolean isOnRoute,
                                     float leftDoorX, float rightDoorX, float leftDoorZ, float rightDoorZ) {
        // Door coordinates are part of geometry because MTR moves door model
        // parts while rendering.  Position, yaw, pitch and light are absent:
        // they belong to the current PoseStack or live vertex attributes.
        String texture = reflectiveText(model, "textureId", "texture", "textureLocation");
        String material = reflectiveText(model, "material", "renderType");
        if (material.equals("unknown")) material = String.valueOf(stage);
        int lod = reflectiveInt(model, "lod", "levelOfDetail");
        String variant = reflectiveText(model, "variant", "modelVariant");
        String identity = "train:" + Integer.toUnsignedString(System.identityHashCode(model))
                + ":" + stage
                + ":car=" + carIndex + ":cars=" + trainCars
                + ":rev=" + reversed + ":route=" + isOnRoute
                + ":variant=" + variant
                + ":doors=" + Float.floatToIntBits(leftDoorX) + ','
                + Float.floatToIntBits(rightDoorX) + ','
                + Float.floatToIntBits(leftDoorZ) + ','
                + Float.floatToIntBits(rightDoorZ);
        return new VertexMeshKey(identity, variant.hashCode(), geometryRevision, texture, material, lod);
    }

    private static String reflectiveText(Object value, String... names) {
        for (String name : names) {
            Object state = reflectiveObject(value, name);
            if (state != null) return String.valueOf(state);
        }
        return "unknown";
    }

    private static int reflectiveInt(Object value, String... names) {
        for (String name : names) {
            Object state = reflectiveObject(value, name);
            if (state instanceof Number number) return number.intValue();
        }
        return 0;
    }

    private static Object reflectiveObject(Object value, String fieldName) {
        try {
            java.lang.reflect.Field field = null;
            for (Class<?> type = value.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                    // Continue through MTR's superclass hierarchy.
                }
            }
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Long reflectiveState(Object value, String fieldName) {
        try {
            java.lang.reflect.Field field = null;
            for (Class<?> type = value.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                    // Continue through MTR's superclass hierarchy.
                }
            }
            if (field == null) return null;
            field.setAccessible(true);
            Object state = field.get(value);
            if (state == null) return 0L;
            if (state instanceof Number number) return number.longValue();
            if (state instanceof Boolean bool) return bool ? 1L : 0L;
            return (long) System.identityHashCode(state);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static float[] matrixValues(Object matrix) {
        float[] values = new float[16];
        try {
            try {
                matrix.getClass().getMethod("store", java.nio.FloatBuffer.class)
                        .invoke(matrix, java.nio.FloatBuffer.wrap(values));
            } catch (NoSuchMethodException oldMapping) {
                try {
                    matrix.getClass().getMethod("get", java.nio.FloatBuffer.class)
                            .invoke(matrix, java.nio.FloatBuffer.wrap(values));
                } catch (NoSuchMethodException jomlBuffer) {
                    matrix.getClass().getMethod("get", float[].class).invoke(matrix, (Object) values);
                }
            }
            return values;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Pose storage is diagnostic/future GPU data.  A failed snapshot
            // must not invalidate an otherwise valid cached draw.
            return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        }
    }

    public record InstancePose(float[] matrix, long lastFrame) {
        public InstancePose(float[] matrix) { this(matrix, 0L); }
        public InstancePose {
            if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("4x4 matrix required");
            matrix = matrix.clone();
        }
        @Override public float[] matrix() { return matrix.clone(); }
    }

    public record PoseStats(long frame, long hits, long misses, long evictions, int size) { }

    public static PoseStats poseStats() {
        synchronized (POSES) {
            return new PoseStats(poseFrame, poseHits, poseMisses, poseEvictions, POSES.size());
        }
    }

    private static final class PoseKey {
        private final ModelTrainBase model;
        private final int carIndex;
        private PoseKey(ModelTrainBase model, int carIndex) {
            this.model = model;
            this.carIndex = carIndex;
        }
        @Override public boolean equals(Object other) {
            return other instanceof PoseKey key && key.model == model && key.carIndex == carIndex;
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(model) + carIndex;
        }
    }
}
