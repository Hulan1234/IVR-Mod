package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.render.MoreRenderLayers;
import net.hulan.ivr.DependenciesChecker;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 可选实例化路径的渲染线程队列；不支持的环境继续使用 CPU 路径。 */
public final class GpuInstanceBatch {
    private static final int MAX_INSTANCES = 16_384;
    private static final int INSTANCE_STAGING_ESTIMATE = 76;
    private static final Logger LOGGER = LogManager.getLogger("ivr");
    private static final Map<Object, List<Submission>> PENDING = new IdentityHashMap<>();
    private static long pendingInstanceBytes;
    private static volatile Backend backend;
    /** 仅由版本专用的类型化 endBatch 钩子设置。 */
    private static volatile boolean typedFlushHookObserved;
    private static long typedFlushFrame = Long.MIN_VALUE;
    private static long frame;
    private static long lastPlatformInstallAttempt = Long.MIN_VALUE;
    private static boolean unflushedReplay;

    private GpuInstanceBatch() { }

    static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(failure);
    }

    /** 不符合显式约定的图层必须使用 CPU 渲染。 */
    public enum LayerContract {
        OPAQUE_ENTITY_CUTOUT,
        TEXTURED_QUADS,
        BEACON_BEAM,
        TRANSLUCENT,
        UNKNOWN
    }

    /** Identifies which path consumed a submitted mesh. */
    public enum ConsumptionStatus {
        NOT_CONSUMED,
        CONSUMED_GPU,
        CONSUMED_CPU,
        PARTIAL_OR_UNKNOWN
    }

    /**
     * 后端已确认完成绘制的实例前缀数量。即使当前实例的绘制结果不确定，
     * 调用方也不应重放这个已确认完成的前缀。
     */
    public record DrawResult(int drawnInstances, boolean ownershipCertain) {

        public DrawResult {
            if (drawnInstances < 0) {
                throw new IllegalArgumentException("Negative draw count");
            }
        }

        public static DrawResult rejected() {
            return new DrawResult(0, true);
        }

        public static DrawResult drawn(int count) {
            return new DrawResult(count, true);
        }

        /** GL 调用可能已到达驱动后才报告错误，且没有已确认完成的实例。 */
        public static DrawResult uncertain() {
            return uncertain(0);
        }

        /**
         * GL 调用可能已到达驱动后才报告错误；count 是当前不确定绘制之前
         * 已确认完成的实例前缀。
         */
        public static DrawResult uncertain(int confirmedDrawnInstances) {
            return new DrawResult(confirmedDrawnInstances, false);
        }

        /** Classifies this result against the number of instances in the draw group. */
        public ConsumptionStatus consumptionStatus(int groupSize) {
            if (groupSize <= 0 || !ownershipCertain || drawnInstances > groupSize) {
                return ConsumptionStatus.PARTIAL_OR_UNKNOWN;
            }
            if (drawnInstances == 0) return ConsumptionStatus.NOT_CONSUMED;
            return drawnInstances == groupSize
                    ? ConsumptionStatus.CONSUMED_GPU
                    : ConsumptionStatus.PARTIAL_OR_UNKNOWN;
        }
    }

    /** 列主序变换矩阵及动态实体属性。 */
    public record Instance(float[] matrix,
                           Object poseMatrix,
                           int packedLight,
                           int packedOverlay,
                           int color) {

        public Instance {
            if (matrix == null || matrix.length != 16) {
                throw new IllegalArgumentException("A 4x4 matrix is required");
            }
            Objects.requireNonNull(poseMatrix, "poseMatrix");
            matrix = matrix.clone();
            poseMatrix = copyMatrix(poseMatrix);
            for (float value : matrix) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Non-finite transform");
                }
            }
        }

        @Override
        public float[] matrix() {
            return matrix.clone();
        }

        public Object poseMatrix() {
            return copyMatrix(poseMatrix);
        }
    }

    /** 一个绘制组使用同一 RenderType、纹理和驻留网格。 */
    public record Group(Object layer,
                        Object texture,
                        LayerContract contract,
                        VertexMeshKey meshKey,
                        VertexMesh mesh,
                        List<Instance> instances) {

        public Group {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(meshKey, "meshKey");
            Objects.requireNonNull(mesh, "mesh");
            if (instances == null || instances.isEmpty()) {
                throw new IllegalArgumentException("Empty GPU group");
            }
            instances = List.copyOf(instances);
        }
    }

    /** 后端必须同时识别实际渲染图层及其纹理绑定。 */
    public interface Backend {

        boolean supports(Object layer, Object texture, LayerContract contract);

        /**
         * 已开始 GPU 绘制后，必须通过不确定的 DrawResult 返回，不能再抛可恢复异常；
         * 这样调用方只会对确认未绘制的分组执行 CPU 回退。
         */
        DrawResult draw(Group group);
    }

    /** 在渲染帧开始时清理过期提交。 */
    public static void beginFrame() {
        requireRenderThread();
        unflushedReplay = false;
        if (!PENDING.isEmpty()) {
            Map<Object, List<Submission>> retained = new IdentityHashMap<>();
            long retainedBytes = 0L;
            boolean retainedUnsafe = false;
            for (Map.Entry<Object, List<Submission>> entry : PENDING.entrySet()) {
                List<Submission> keep = new ArrayList<>();
                for (List<Submission> group : contiguousGroups(entry.getValue())) {
                    if (!hasSafeCpuFallback(group)) {
                        keep.addAll(group);
                        retainedUnsafe = true;
                        continue;
                    }
                    // A missing typed flush must not silently drop a queued draw.
                    unflushedReplay = true;
                    replaySubmissions(group, 0);
                }
                if (!keep.isEmpty()) {
                    retained.put(entry.getKey(), keep);
                    retainedBytes += (long) keep.size() * INSTANCE_STAGING_ESTIMATE;
                }
            }
            PENDING.clear();
            PENDING.putAll(retained);
            pendingInstanceBytes = retainedBytes;
            typedFlushHookObserved = false;
            backend = null;
            if (retainedUnsafe || unflushedReplay) {
                LOGGER.warn("IVR GPU batch flush hook stopped firing; disabling GPU submissions for this client");
            }
        }
        frame++;
    }

    public static void installBackend(Backend candidate) {
        requireRenderThread();
        if (candidate != null && !isGpuBackendAdmissionAllowed()) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
            backend = null;
            return;
        }
        backend = candidate;
    }

    public static void disableBackend() {
        backend = null;
    }

    public static void disableAndClear() {
        backend = null;
        typedFlushHookObserved = false;
        PENDING.clear();
        pendingInstanceBytes = 0L;
    }

    /**
     * 首次类型化 flush 证明当前版本的 MultiBufferSource.BufferSource 钩子已生效。
     * 在此之前使用普通 CPU 路径，避免可选 Mixin 缺失时网格无人消费。
     */
    public static void markTypedFlushHookAvailable() {
        requireRenderThread();
        typedFlushHookObserved = true;
        typedFlushFrame = frame;
    }

    /**
     * 仅当几何所有权转交给此队列时返回 true。返回 false 时调用方必须保留 CPU
     * 路径，并提供后端在绘制前拒绝整个分组时使用的 CPU 重放闭包。
     */
    public static boolean submit(Object layer, Object texture, LayerContract contract,
                                 VertexMeshKey key, VertexMesh mesh, Instance instance,
                                 Runnable cpuFallback) {
        requireRenderThread();
        if (!isGpuBackendAdmissionAllowed()) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
            return false;
        }
        Backend current = backend;
        if (current == null || !typedFlushHookObserved) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
            return false;
        }
        if (contract != LayerContract.OPAQUE_ENTITY_CUTOUT || layer == null) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.RENDER_TYPE_CONTRACT);
            return false;
        }
        if (key == null) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.KEY_STABILITY);
            return false;
        }
        if (isCpuOnlyKey(key)) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.RENDER_TYPE_CONTRACT);
            return false;
        }
        if (mesh == null || !GpuMeshCache.isPublished(key, mesh)) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.MESH_NOT_READY);
            return false;
        }
        if (instance == null) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BUFFER_SOURCE);
            return false;
        }
        if (!isSupportedGpuLayer(layer, texture)) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.TEXTURE_MATERIAL);
            return false;
        }
        if (!isStableModel(key)) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.KEY_STABILITY);
            return false;
        }
        try {
            if (!current.supports(layer, texture, contract)) {
                RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.RENDER_TYPE_CONTRACT);
                return false;
            }
        } catch (RuntimeException | LinkageError ignored) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
            return false;
        }
        if (pendingCount() >= MAX_INSTANCES) {
            IVRMemoryBudget.recordReject("staging");
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.CAPACITY);
            return false;
        }
        if (mesh.byteSize() > IVRMemoryBudget.gpuMeshBudgetBytes()) {
            IVRMemoryBudget.recordReject("gpu");
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.CAPACITY);
            return false;
        }
        long stagingLimit = IVRMemoryBudget.stagingBufferBudgetBytes();
        if (pendingInstanceBytes + INSTANCE_STAGING_ESTIMATE > stagingLimit) {
            IVRMemoryBudget.recordReject("staging");
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.CAPACITY);
            return false;
        }
        PENDING.computeIfAbsent(layer, ignored -> new ArrayList<>())
                .add(new Submission(texture, contract, key, mesh, instance, cpuFallback));
        pendingInstanceBytes += INSTANCE_STAGING_ESTIMATE;
        RenderDebugStats.recordGpuAdmission();
        return true;
    }

    /**
     * 供月台门渲染器调用的便捷入口。它会复制当前姿态，以便调用方弹出临时变换后，
     * 若 endBatch 拒绝 GPU 提交，仍可按原姿态重放 CPU 几何。
     */
    public static boolean submitOrFallback(Object layer, Object texture, VertexMeshKey key,
                                            VertexMesh mesh, PoseStack matrices,
                                            int light, int overlay, VertexConsumer consumer) {
        return submitOrFallbackStatus(layer, texture, key, mesh, matrices, light, overlay, consumer)
                == ConsumptionStatus.CONSUMED_GPU;
    }

    /** Compatibility detail for callers that must distinguish CPU replay from rejection. */
    public static ConsumptionStatus submitOrFallbackStatus(Object layer, Object texture, VertexMeshKey key,
                                                            VertexMesh mesh, PoseStack matrices,
                                                            int light, int overlay, VertexConsumer consumer) {
        requireRenderThread();
        installPlatformBackendIfSupported();
        PoseStack snapshot;
        try {
            snapshot = copyPose(matrices);
        } catch (RuntimeException unavailable) {
            return replayCpu(() -> mesh.replay(matrices, consumer, light, overlay));
        }
        Runnable fallback = () -> mesh.replay(snapshot, consumer, light, overlay);
        float[] matrix;
        try {
            matrix = matrixValues(snapshot.last().pose());
        } catch (RuntimeException unavailable) {
            return replayCpu(fallback);
        }
        Instance instance;
        try {
            instance = new Instance(matrix, snapshot.last().pose(), light, overlay, -1);
        } catch (RuntimeException | LinkageError unavailable) {
            return replayCpu(fallback);
        }
        try {
            if (!submit(layer, texture, contractFor(layer, texture), key, mesh, instance, fallback)) {
                return replayCpu(fallback);
            }
        } catch (RuntimeException | LinkageError failure) {
            // submit only stages work; queue insertion is the ownership transfer point.
            disableBackend();
            return replayCpu(fallback);
        }
        return ConsumptionStatus.CONSUMED_GPU;
    }

    private static LayerContract contractFor(Object layer, Object texture) {
        return isSupportedGpuLayer(layer, texture) ? LayerContract.OPAQUE_ENTITY_CUTOUT : LayerContract.UNKNOWN;
    }

    /** 提交首个月台门实例前加载版本专用后端。 */
    private static void installPlatformBackendIfSupported() {
        if (!IVRConfig.gpuOptimization || backend != null || lastPlatformInstallAttempt == frame) {
            return;
        }
        lastPlatformInstallAttempt = frame;
        if (!isGpuBackendAdmissionAllowed()) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
            return;
        }
        try {
            Class<?> backendClass = Class.forName("net.hulan.ivr.render_promotion.mesh.GpuMeshBackend", true,
                    GpuInstanceBatch.class.getClassLoader());
            backendClass.getMethod("installIfSupported").invoke(null);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // 缺少或不支持版本适配器时继续使用普通 CPU 路径。
        }
    }

    /**
     * 在类型化 BufferSource 的 HEAD 钩子中刷新一个 RenderType，此时 CPU 构建器
     * 仍有效，可用于回退重放。
     */
    public static boolean flush(Object layer) {
        requireRenderThread();
        if (layer == null) {
            return false;
        }
        List<Submission> submissions = PENDING.remove(layer);
        if (submissions == null || submissions.isEmpty()) {
            return false;
        }
        pendingInstanceBytes = Math.max(0L,
                pendingInstanceBytes - (long) submissions.size() * INSTANCE_STAGING_ESTIMATE);
        Backend current = backend;
        boolean replayedToCpu = false;
        List<Submission> retained = new ArrayList<>();
        List<List<Submission>> groups = contiguousGroups(submissions);
        /*
         * 此 HEAD 钩子仍处于原始 BufferSource 构建器中。如果某个分组确定不受支持，
         * 则在 vanilla endBatch 前将整个图层重放到 CPU，避免后面的 GPU 分组越过前面的 CPU 分组。
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
                } catch (RuntimeException | LinkageError ignored) {
                    gpuPlan = false;
                    break;
                }
            }
        }
        if (!gpuPlan) {
            RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.RENDER_TYPE_CONTRACT);
            for (List<Submission> group : groups) {
                if (hasSafeCpuFallback(group)) {
                    replayedToCpu |= replaySubmissions(group, 0);
                } else {
                    retained.addAll(group);
                    disableBackend();
                }
            }
            retainPending(layer, retained);
            return replayedToCpu;
        }
        for (List<Submission> group : groups) {
            if (current == null || backend != current) {
                RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.BACKEND_CONTEXT);
                if (hasSafeCpuFallback(group)) replayedToCpu |= replaySubmissions(group, 0);
                else retained.addAll(group);
                continue;
            }
            Submission first = group.get(0);
            Group drawGroup = new Group(layer, first.texture, first.contract, first.key, first.mesh,
                    group.stream().map(submission -> submission.instance).toList());
            try {
                RenderDebugStats.recordGpuDrawAttempt(1L);
                DrawResult result = current.draw(drawGroup);
                if (result == null) {
                    RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.DRAW_FAILURE);
                    disableBackend();
                    if (hasSafeCpuFallback(group)) replayedToCpu |= replaySubmissions(group, 0);
                    else retained.addAll(group);
                    continue;
                }
                ConsumptionStatus status = result.consumptionStatus(group.size());
                if (status == ConsumptionStatus.NOT_CONSUMED) {
                    RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.DRAW_FAILURE);
                    if (hasSafeCpuFallback(group)) replayedToCpu |= replaySubmissions(group, 0);
                    else {
                        retained.addAll(group);
                        disableBackend();
                    }
                } else if (status == ConsumptionStatus.CONSUMED_GPU) {
                    RenderDebugStats.recordCooperativeSubmission();
                    RenderDebugStats.recordGpuDraw(group.size());
                } else if (status == ConsumptionStatus.PARTIAL_OR_UNKNOWN) {
                    RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.DRAW_FAILURE);
                    // Uncertain or partial ownership is handled exactly once, then
                    // all later groups stay on CPU after the backend is disabled.
                    disableBackend();
                    int replayFrom = Math.min(result.drawnInstances(), group.size());
                    if (hasSafeCpuFallback(group)) replayedToCpu |= replaySubmissions(group, replayFrom);
                    else retained.addAll(group);
                }
            } catch (RuntimeException | LinkageError failure) {
                RenderDebugStats.recordGpuReject(RenderDebugStats.GpuRejectReason.DRAW_FAILURE);
                // A backend exception leaves ownership unknown; replay once and
                // disable the backend before processing any later group.
                disableBackend();
                if (hasSafeCpuFallback(group)) replayedToCpu |= replaySubmissions(group, 0);
                else retained.addAll(group);
            }
        }
        retainPending(layer, retained);
        return replayedToCpu;
    }

    /** 处理本轮没有已启动 CPU 缓冲的图层。 */
    public static void flushAll() {
        requireRenderThread();
        for (Object layer : new ArrayList<>(PENDING.keySet())) flush(layer);
    }

    /** 世界或资源版本变化时清空待提交项。 */
    public static void invalidate() {
        Runnable clear = () -> {
            PENDING.clear();
            pendingInstanceBytes = 0L;
        };
        if (RenderSystem.isOnRenderThreadOrInit()) {
            clear.run();
        } else {
            RenderSystem.recordRenderCall(clear::run);
        }
    }

    public static long frame() { return frame; }
    public static boolean enabled() { return backend != null; }
    public static boolean typedFlushHookObserved() { return typedFlushHookObserved; }
    public static boolean unflushedReplay() { return unflushedReplay; }

    public static boolean hasTypedConsumer(String namespace) {
        requireRenderThread();
        if (namespace == null || !typedFlushHookObserved || backend == null) {
            return false;
        }
        return !namespace.equals("nte-rail");
    }

    private static boolean isGpuBackendAdmissionAllowed() {
        try {
            return DependenciesChecker.isGpuMeshBackendAllowed();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean isStableOpaqueMesh(VertexMeshKey key, Object layer, Object texture) {
        return isSupportedGpuLayer(layer, texture) && isStableModel(key);
    }

    private static boolean isStableModel(VertexMeshKey key) {
        if (key == null) return false;
        String model = key.model();
        return model.equals("ivr:door") || model.startsWith("BlockEntity:")
                || model.startsWith("EyeCandy:") || model.startsWith("msd-catenary:");
    }

    public static boolean isSupportedGpuLayer(Object layer, Object texture) {
        if (!(layer instanceof RenderType) || !(texture instanceof ResourceLocation)) {
            return false;
        }
        ResourceLocation location = (ResourceLocation) texture;
        try {
            if (MoreRenderLayers.getExterior(location) == layer) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) { }
        try {
            if (MoreRenderLayers.getInterior(location) == layer) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) { }
        for (String methodName : new String[]{"entityCutout", "entityCutoutNoCull", "entityCutoutNoCullZOffset"}) {
            try {
                for (java.lang.reflect.Method method : RenderType.class.getMethods()) {
                    if (!method.getName().equals(methodName) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isAssignableFrom(ResourceLocation.class)
                            && method.invoke(null, location) == layer) {
                        return true;
                    }
                    if (parameters.length == 2 && parameters[0].isAssignableFrom(ResourceLocation.class)
                            && parameters[1] == boolean.class && method.invoke(null, location, false) == layer) {
                        return true;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
        return false;
    }

    /** 兼容仍使用旧名称的版本适配器。 */
    public static boolean isSupportedOpaqueLayer(Object layer, Object texture) {
        return isSupportedGpuLayer(layer, texture);
    }
    public static int pendingCount() {
        int count = 0;
        for (List<Submission> submissions : PENDING.values()) count += submissions.size();
        return count;
    }

    /** ANTE rail callbacks have no typed GPU consumer and stay on CPU. */
    private static boolean isCpuOnlyKey(VertexMeshKey key) {
        String model = key.model();
        return model.startsWith("nte-rail:");
    }

    /** 保持不匹配绘制的原有顺序；只合并相邻且几何相同的批次。 */
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

    private static boolean hasSafeCpuFallback(List<Submission> group) {
        for (Submission submission : group) {
            if (submission.cpuFallback == null) return false;
        }
        return true;
    }

    private static boolean replaySubmissions(List<Submission> submissions, int fromIndex) {
        boolean replayed = false;
        int start = Math.max(0, Math.min(fromIndex, submissions.size()));
        for (int index = start; index < submissions.size(); index++) {
            Runnable fallback = submissions.get(index).cpuFallback;
            if (fallback == null) continue;
            try {
                RenderDebugStats.recordGpuFallback();
                fallback.run();
                replayed = true;
            } catch (RuntimeException | LinkageError ignored) {
                // One failed replay must not prevent other independent instances.
            }
        }
        return replayed;
    }

    private static void retainPending(Object layer, List<Submission> submissions) {
        if (submissions.isEmpty()) return;
        PENDING.put(layer, submissions);
        pendingInstanceBytes += (long) submissions.size() * INSTANCE_STAGING_ESTIMATE;
        disableBackend();
    }

    private static ConsumptionStatus replayCpu(Runnable fallback) {
        if (fallback == null) return ConsumptionStatus.PARTIAL_OR_UNKNOWN;
        try {
            RenderDebugStats.recordGpuFallback();
            fallback.run();
            return ConsumptionStatus.CONSUMED_CPU;
        } catch (RuntimeException | LinkageError failure) {
            disableBackend();
            return ConsumptionStatus.PARTIAL_OR_UNKNOWN;
        }
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            throw new IllegalStateException("Render thread required");
        }
    }

    private static PoseStack copyPose(PoseStack source) {
        PoseStack copy = new PoseStack();
        multiplyMatrix(copy.last().pose(), source.last().pose());
        multiplyMatrix(copy.last().normal(), source.last().normal());
        return copy;
    }

    /** 1.19.4 起 PoseStack 矩阵从 Mojang Matrix4f/Matrix3f 切换为 JOML。 */
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

    /** 复制 1.19.4 前的 Mojang 矩阵或 1.19.4 的 JOML 矩阵。 */
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

    private record Submission(Object texture,
                              LayerContract contract,
                              VertexMeshKey key,
                              VertexMesh mesh,
                              Instance instance,
                              Runnable cpuFallback) {
    }
    private record GroupKey(Object texture,
                            LayerContract contract,
                            VertexMeshKey key,
                            VertexMesh mesh) {
    }
}
