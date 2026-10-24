package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.render.MoreRenderLayers;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Render-thread queue for the optional instanced path. Unsupported contexts stay on CPU. */
public final class GpuInstanceBatch {
    private static final int MAX_INSTANCES = 16_384;
    private static final Logger LOGGER = LogManager.getLogger("ivr");
    /** GPU is an explicit opt-in; an unsupported or unverified context stays on CPU. */
    private static final boolean PLATFORM_BACKEND_ENABLED =
            "true".equalsIgnoreCase(System.getProperty("ivr.gpuDoorInstances", "false"));
    private static final Map<Object, List<Submission>> PENDING = new IdentityHashMap<>();
    private static volatile Backend backend;
    /** Set only by the version-specific typed endBatch hook. */
    private static volatile boolean typedFlushHookObserved;
    private static long typedFlushFrame = Long.MIN_VALUE;
    private static long frame;
    private static long lastPlatformInstallAttempt = Long.MIN_VALUE;

    private GpuInstanceBatch() { }

    /** Layers outside this explicit contract must use CPU rendering. */
    public enum LayerContract {
        OPAQUE_ENTITY_CUTOUT,
        BEACON_BEAM,
        TRANSLUCENT,
        UNKNOWN
    }

    /**
     * The number of instances that the backend owns after returning.  A
     * backend can fail after drawing a prefix of a group, so the caller must
     * replay only the suffix.  This keeps fallback exactly-once and avoids
     * double rendering when a later GL call fails.
     */
    public record DrawResult(int drawnInstances, boolean ownershipCertain) {
        public DrawResult {
            if (drawnInstances < 0) throw new IllegalArgumentException("Negative draw count");
        }
        public static DrawResult rejected() { return new DrawResult(0, true); }
        public static DrawResult drawn(int count) { return new DrawResult(count, true); }
        /** A GL call may have reached the driver before reporting an error. */
        public static DrawResult uncertain() { return new DrawResult(0, false); }
    }

    /** Column-major transform plus the dynamic entity attributes. */
    public record Instance(float[] matrix, Object poseMatrix, int packedLight, int packedOverlay, int color) {
        public Instance {
            if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("A 4x4 matrix is required");
            Objects.requireNonNull(poseMatrix, "poseMatrix");
            matrix = matrix.clone();
            poseMatrix = copyMatrix(poseMatrix);
            for (float value : matrix) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite transform");
            }
        }
        @Override public float[] matrix() { return matrix.clone(); }
        @Override public Object poseMatrix() { return copyMatrix(poseMatrix); }
    }

    /** A draw group has one exact RenderType, texture and resident mesh. */
    public record Group(Object layer, Object texture, LayerContract contract, VertexMeshKey meshKey,
                        VertexMesh mesh, List<Instance> instances) {
        public Group {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(meshKey, "meshKey");
            Objects.requireNonNull(mesh, "mesh");
            if (instances == null || instances.isEmpty()) throw new IllegalArgumentException("Empty GPU group");
            instances = List.copyOf(instances);
        }
    }

    /** Backends must recognize both the real render layer and its texture binding. */
    public interface Backend {
        boolean supports(Object layer, Object texture, LayerContract contract);
        DrawResult draw(Group group);
    }

    /** Reset stale submissions at the start of a render frame. */
    public static void beginFrame() {
        requireRenderThread();
        if (typedFlushHookObserved && typedFlushFrame < frame && !PENDING.isEmpty()) {
            // A hook that stopped firing would otherwise leave submissions
            // owned by a dead buffer source until the next frame.  They cannot
            // be replayed after the source is closed, so drop the queue and
            // force CPU mode until a fresh typed hook is observed.
            PENDING.clear();
            typedFlushHookObserved = false;
            backend = null;
            LOGGER.warn("IVR GPU batch flush hook stopped firing; disabling GPU submissions for this client");
        }
        frame++;
        PENDING.clear();
    }

    /** Backend installation is explicit; null keeps all callers on CPU rendering. */
    public static void installBackend(Backend candidate) {
        requireRenderThread();
        backend = candidate;
    }

    /**
     * Stops accepting new submissions when a render context disappeared before
     * a queued GL release callback could run. Pending CPU replays are discarded
     * by the next render-thread frame boundary.
     */
    public static void disableBackend() {
        backend = null;
    }

    /**
     * Bootstrap-safe shutdown used when an optional Mixin contract is not
     * present.  No render-thread assertion is made because Mixin plugins run
     * before the client render thread exists.
     */
    public static void disableAndClear() {
        backend = null;
        typedFlushHookObserved = false;
        PENDING.clear();
    }

    /**
     * The first typed flush is a runtime proof that this version's
     * MultiBufferSource.BufferSource hook resolved. Until then submissions
     * stay on the ordinary CPU path, so a missing optional mixin cannot leave
     * a queued mesh with no consumer.
     */
    public static void markTypedFlushHookAvailable() {
        requireRenderThread();
        typedFlushHookObserved = true;
        typedFlushFrame = frame;
    }

    /**
     * Returns true only when ownership of the geometry moves to this queue.
     * Callers must keep their CPU path if this returns false and provide a CPU
     * replay closure for the case where the backend rejects the group pre-draw.
     */
    public static boolean submit(Object layer, Object texture, LayerContract contract,
                                 VertexMeshKey key, VertexMesh mesh, Instance instance,
                                 Runnable cpuFallback) {
        requireRenderThread();
        Backend current = backend;
        if (current == null || !typedFlushHookObserved || contract != LayerContract.OPAQUE_ENTITY_CUTOUT
                || layer == null || key == null || mesh == null || instance == null || cpuFallback == null
                || isCpuOnlyKey(key) || !GpuMeshCache.isPublished(key, mesh)) return false;
        try {
            if (!current.supports(layer, texture, contract)) return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        if (pendingCount() >= MAX_INSTANCES) return false;
        PENDING.computeIfAbsent(layer, ignored -> new ArrayList<>())
                .add(new Submission(texture, contract, key, mesh, instance, cpuFallback));
        return true;
    }

    /**
     * Door-renderer convenience entry point. It snapshots the current pose so
     * a later endBatch rejection can replay the exact CPU geometry after the
     * caller has popped its temporary transform.
     */
    public static boolean submitOrFallback(Object layer, Object texture, VertexMeshKey key,
                                            VertexMesh mesh, PoseStack matrices,
                                            int light, int overlay, VertexConsumer consumer) {
        requireRenderThread();
        installPlatformBackendIfSupported();
        PoseStack snapshot;
        try {
            snapshot = copyPose(matrices);
        } catch (RuntimeException unavailable) {
            mesh.replay(matrices, consumer, light, overlay);
            return false;
        }
        Runnable fallback = () -> mesh.replay(snapshot, consumer, light, overlay);
        float[] matrix;
        try {
            matrix = matrixValues(snapshot.last().pose());
        } catch (RuntimeException unavailable) {
            fallback.run();
            return false;
        }
        if (!submit(layer, texture, LayerContract.OPAQUE_ENTITY_CUTOUT, key, mesh,
                new Instance(matrix, snapshot.last().pose(), light, overlay, -1), fallback)) {
            fallback.run();
            return false;
        }
        return true;
    }

    /** Load the version-specific backend before the first door instance is submitted. */
    private static void installPlatformBackendIfSupported() {
        if (!PLATFORM_BACKEND_ENABLED || backend != null || lastPlatformInstallAttempt == frame) return;
        lastPlatformInstallAttempt = frame;
        try {
            Class<?> backendClass = Class.forName("net.hulan.ivr.render_promotion.mesh.GpuMeshBackend", true,
                    GpuInstanceBatch.class.getClassLoader());
            backendClass.getMethod("installIfSupported").invoke(null);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Missing/unsupported version adapter leaves the ordinary CPU path active.
        }
    }

    /**
     * Flushes exactly one render type at the typed BufferSource HEAD hook,
     * while its CPU builder is still valid for fallback replay.
     */
    public static boolean flush(Object layer) {
        requireRenderThread();
        if (layer == null) return false;
        List<Submission> submissions = PENDING.remove(layer);
        if (submissions == null || submissions.isEmpty()) return false;
        Backend current = backend;
        boolean replayedToCpu = false;
        List<List<Submission>> groups = contiguousGroups(submissions);
        /*
         * A CPU fallback is still inside the original BufferSource builder at
         * this HEAD hook. If one group is known to be unsupported, replay the
         * whole layer on CPU before the vanilla endBatch call. Otherwise a GPU
         * group later in the list could overtake an earlier CPU group.
         */
        boolean gpuPlan = current != null;
        if (gpuPlan) {
            for (List<Submission> group : groups) {
                Submission first = group.get(0);
                try {
                    if (!current.supports(layer, first.texture, first.contract)) {
                        gpuPlan = false;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    gpuPlan = false;
                    break;
                }
            }
        }
        if (!gpuPlan) {
            for (List<Submission> group : groups) {
                for (Submission submission : group) {
                    try {
                        submission.cpuFallback.run();
                        replayedToCpu = true;
                    } catch (RuntimeException ignored) {
                        // One failed replay must not prevent other independent instances.
                    }
                }
            }
            return replayedToCpu;
        }
        for (List<Submission> group : groups) {
            Submission first = group.get(0);
            Group drawGroup = new Group(layer, first.texture, first.contract, first.key, first.mesh,
                    group.stream().map(submission -> submission.instance).toList());
            try {
                DrawResult result = current.draw(drawGroup);
                if (result == null) {
                    disableBackend();
                    for (Submission submission : group) {
                        try {
                            submission.cpuFallback.run();
                            replayedToCpu = true;
                        } catch (RuntimeException ignored) { }
                    }
                    continue;
                }
                if (!result.ownershipCertain()) {
                    // The backend cannot prove which instances reached GL. Keep
                    // the original consumer alive and replay the complete group
                    // so a failed optional path cannot make a train or door
                    // disappear. The backend is disabled before the replay.
                    disableBackend();
                    for (Submission submission : group) {
                        try {
                            submission.cpuFallback.run();
                            replayedToCpu = true;
                        } catch (RuntimeException ignored) { }
                    }
                    continue;
                }
                if (result.drawnInstances() > group.size()) {
                    disableBackend();
                    for (Submission submission : group) {
                        try {
                            submission.cpuFallback.run();
                            replayedToCpu = true;
                        } catch (RuntimeException ignored) { }
                    }
                    continue;
                }
                int drawn = result.drawnInstances();
                for (int index = drawn; index < group.size(); index++) {
                    try {
                        group.get(index).cpuFallback.run();
                        replayedToCpu = true;
                    } catch (RuntimeException ignored) {
                        // One failed replay must not prevent other independent instances.
                    }
                }
            } catch (Throwable failure) {
                /*
                 * Version backends own their partial-draw accounting and
                 * return a DrawResult from their internal failure paths. A
                 * backend that violates that contract is disabled here; its
                 * group is replayed while the original consumer is still
                 * alive, preventing the old silent lost-render path.
                 */
                disableBackend();
                for (Submission submission : group) {
                    try {
                        submission.cpuFallback.run();
                        replayedToCpu = true;
                    } catch (RuntimeException ignored) {
                        // One failed replay must not prevent other independent instances.
                    }
                }
            }
        }
        return replayedToCpu;
    }

    /** Covers layers that did not have a started CPU buffer in this pass. */
    public static void flushAll() {
        requireRenderThread();
        for (Object layer : new ArrayList<>(PENDING.keySet())) flush(layer);
    }

    /** Clear submissions when world/resource generations change. */
    public static void invalidate() {
        if (RenderSystem.isOnRenderThreadOrInit()) PENDING.clear();
        else RenderSystem.recordRenderCall(PENDING::clear);
    }

    public static long frame() { return frame; }
    public static boolean enabled() { return backend != null; }
    public static boolean typedFlushHookObserved() { return typedFlushHookObserved; }

    /**
     * Reports whether a namespace has a typed consumer that can own an
     * asynchronously built mesh.  The ANTE and MSD callback caches currently
     * have no RenderType/VBO consumer, so allowing them to record a second
     * mesh beside ANTE's authoritative rebuild would only duplicate CPU work.
     * Keep this check on the render thread so a future version adapter can
     * enable the path by installing its backend and flush hook atomically.
     */
    public static boolean hasTypedConsumer(String namespace) {
        requireRenderThread();
        if (namespace == null || !typedFlushHookObserved || backend == null) return false;
        return !namespace.equals("nte-rail") && !namespace.equals("msd-catenary");
    }

    /**
     * Only layers whose texture and vanilla/MTR factory identity are known are
     * eligible for the cached path. This admits entity cutout variants across
     * mappings while keeping translucent, beacon and shader supplied layers on
     * the original CPU batch.
     */
    public static boolean isSupportedOpaqueLayer(Object layer, Object texture) {
        if (!(layer instanceof RenderType) || !(texture instanceof ResourceLocation)) return false;
        ResourceLocation location = (ResourceLocation) texture;
        try {
            if (MoreRenderLayers.getExterior(location) == layer) return true;
        } catch (RuntimeException | LinkageError ignored) { }
        for (String methodName : new String[]{"entityCutout", "entityCutoutNoCull", "entityCutoutNoCullZOffset"}) {
            try {
                java.lang.reflect.Method method = RenderType.class.getMethod(methodName, ResourceLocation.class);
                if (method.invoke(null, location) == layer) return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
        return false;
    }
    public static int pendingCount() {
        int count = 0;
        for (List<Submission> submissions : PENDING.values()) count += submissions.size();
        return count;
    }

    /** Namespaces without a typed GPU consumer remain CPU-only by contract. */
    private static boolean isCpuOnlyKey(VertexMeshKey key) {
        String model = key.model();
        return model.startsWith("msd-catenary:") || model.startsWith("nte-rail:");
    }

    /** Keep non-matching draws in their original order; only adjacent equal geometry batches. */
    private static List<List<Submission>> contiguousGroups(List<Submission> submissions) {
        List<List<Submission>> groups = new ArrayList<>();
        GroupKey currentKey = null;
        List<Submission> currentGroup = null;
        for (Submission submission : submissions) {
            GroupKey key = new GroupKey(submission.texture, submission.contract, submission.key, submission.mesh);
            if (!key.equals(currentKey)) {
                currentKey = key;
                currentGroup = new ArrayList<>();
                groups.add(currentGroup);
            }
            currentGroup.add(submission);
        }
        return groups;
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThreadOrInit()) throw new IllegalStateException("Render thread required");
    }

    private static PoseStack copyPose(PoseStack source) {
        PoseStack copy = new PoseStack();
        multiplyMatrix(copy.last().pose(), source.last().pose());
        multiplyMatrix(copy.last().normal(), source.last().normal());
        return copy;
    }

    /** PoseStack moved from Mojang's Matrix4f/Matrix3f to JOML in 1.19.4. */
    private static void multiplyMatrix(Object destination, Object source) {
        try {
            for (java.lang.reflect.Method method : destination.getClass().getMethods()) {
                String name = method.getName();
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && (name.equals("multiply") || name.equals("mul"))
                        && parameters[0].isAssignableFrom(source.getClass())) {
                    method.invoke(destination, source);
                    return;
                }
            }
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalArgumentException("Unable to snapshot pose stack", failure);
        }
        throw new IllegalArgumentException("Unsupported pose stack matrix type " + destination.getClass().getName());
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
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalArgumentException("Unable to read pose matrix", failure);
        }
    }

    /** Snapshot either Mojang's pre-1.19.4 matrix or 1.19.4's JOML matrix. */
    private static Object copyMatrix(Object matrix) {
        try {
            for (java.lang.reflect.Constructor<?> constructor : matrix.getClass().getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 1 && parameters[0].isAssignableFrom(matrix.getClass()))
                    return constructor.newInstance(matrix);
            }
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalArgumentException("Unable to snapshot pose matrix", failure);
        }
        throw new IllegalArgumentException("Unsupported pose matrix type " + matrix.getClass().getName());
    }

    private record Submission(Object texture, LayerContract contract, VertexMeshKey key,
                              VertexMesh mesh, Instance instance, Runnable cpuFallback) { }
    private record GroupKey(Object texture, LayerContract contract, VertexMeshKey key, VertexMesh mesh) { }
}
