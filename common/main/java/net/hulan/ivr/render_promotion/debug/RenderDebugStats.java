package net.hulan.ivr.render_promotion.debug;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import net.hulan.ivr.render_promotion.visibility.Visibility;

import java.util.ArrayList;
import java.util.List;

/** Low-overhead CPU/GPU diagnostics for the F3 overlay. */
public final class RenderDebugStats {

    public enum CpuPhase {
        KEY,
        CAPTURE,
        STABILITY,
        REPLAY
    }

    public enum GpuRejectReason {
        RENDER_TYPE_CONTRACT("rt"),
        KEY_STABILITY("key"),
        TEXTURE_MATERIAL("tex"),
        BACKEND_CONTEXT("backend"),
        MESH_NOT_READY("mesh"),
        BUFFER_SOURCE("buffer"),
        CAPACITY("capacity"),
        DRAW_FAILURE("draw");

        private final String label;

        GpuRejectReason(String label) {
            this.label = label;
        }
    }

    /** Kept for source compatibility with visibility instrumentation. */
    public enum Category {
        BE("BE"),
        RAILBOX("Railbox"),
        TRAIN("Train"),
        CATENARY("Catenary"),
        EYE_CANDY("EyeCandy");

        private final String label;

        Category(String label) {
            this.label = label;
        }
    }

    private static long frameCpuFallbacks;
    private static long frameGpuFallbacks;
    private static long frameGpuRejects;
    private static long frameGpuAdmissions;
    private static long frameGpuMeshReady;
    private static long frameGpuUploads;
    private static long frameGpuDrawAttempts;
    private static long frameGpuDraws;
    private static long frameMeshCacheHits;
    private static long frameMeshCacheMisses;
    private static long frameMeshCacheRejects;
    private static final long[] frameCpuPhaseNanos = new long[CpuPhase.values().length];
    private static final long[] frameCpuPhaseSamples = new long[CpuPhase.values().length];
    private static final long[] frameGpuRejectReasons = new long[GpuRejectReason.values().length];

    private static long totalCpuFallbacks;
    private static long totalGpuFallbacks;
    private static long totalGpuRejects;
    private static long totalGpuAdmissions;
    private static long totalGpuMeshReady;
    private static long totalGpuUploads;
    private static long totalGpuDrawAttempts;
    private static long totalGpuDraws;
    private static long totalMeshCacheHits;
    private static long totalMeshCacheMisses;
    private static long totalMeshCacheRejects;

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile Diagnostics diagnostics = Diagnostics.empty();
    private static volatile boolean active;
    private static volatile boolean overlayRequested;

    private RenderDebugStats() {}

    /** Called by the debug overlay; the next render frame enables CPU/GPU collection. */
    public static void requestOverlay() {
        overlayRequested = true;
    }

    /** Advances the one-frame handoff between the overlay and the renderer. */
    public static void beginFrame() {
        active = overlayRequested && (IVRConfig.cpuOptimization || IVRConfig.gpuOptimization);
        overlayRequested = false;
        if (!active) return;
        frameCpuFallbacks = 0L;
        frameGpuFallbacks = 0L;
        frameGpuRejects = 0L;
        frameGpuAdmissions = 0L;
        frameGpuMeshReady = 0L;
        frameGpuUploads = 0L;
        frameGpuDrawAttempts = 0L;
        frameGpuDraws = 0L;
        frameMeshCacheHits = 0L;
        frameMeshCacheMisses = 0L;
        frameMeshCacheRejects = 0L;
        java.util.Arrays.fill(frameCpuPhaseNanos, 0L);
        java.util.Arrays.fill(frameCpuPhaseSamples, 0L);
        java.util.Arrays.fill(frameGpuRejectReasons, 0L);
    }

    /** Publishes an immutable copy after the render frame has finished. */
    public static void endFrame() {
        if (!active) return;
        totalCpuFallbacks += frameCpuFallbacks;
        totalGpuFallbacks += frameGpuFallbacks;
        totalGpuRejects += frameGpuRejects;
        totalGpuAdmissions += frameGpuAdmissions;
        totalGpuMeshReady += frameGpuMeshReady;
        totalGpuUploads += frameGpuUploads;
        totalGpuDrawAttempts += frameGpuDrawAttempts;
        totalGpuDraws += frameGpuDraws;
        totalMeshCacheHits += frameMeshCacheHits;
        totalMeshCacheMisses += frameMeshCacheMisses;
        totalMeshCacheRejects += frameMeshCacheRejects;
        diagnostics = new Diagnostics(frameGpuAdmissions, frameGpuMeshReady, frameGpuUploads,
                frameGpuDrawAttempts, frameGpuDraws, frameGpuRejects, frameCpuFallbacks,
                frameGpuFallbacks,
                frameMeshCacheHits, frameMeshCacheMisses, frameMeshCacheRejects,
                totalGpuAdmissions, totalGpuMeshReady, totalGpuUploads, totalGpuDrawAttempts,
                totalGpuDraws, totalGpuRejects, totalCpuFallbacks, totalGpuFallbacks,
                totalMeshCacheHits,
                totalMeshCacheMisses, totalMeshCacheRejects,
                frameCpuPhaseNanos.clone(), frameCpuPhaseSamples.clone(), frameGpuRejectReasons.clone());
        snapshot = Snapshot.empty();
    }

    public static boolean enabled() {
        return active;
    }

    public static long startTiming() {
        return 0L;
    }

    public static long elapsedSince(long started) {
        return 0L;
    }

    public static long startCpuTiming() {
        return active && IVRConfig.cpuOptimization ? System.nanoTime() : 0L;
    }

    public static void recordCpuPhase(CpuPhase phase, long started) {
        if (!active || !IVRConfig.cpuOptimization || phase == null || started == 0L) return;
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0L) return;
        int index = phase.ordinal();
        frameCpuPhaseNanos[index] += elapsed;
        frameCpuPhaseSamples[index]++;
    }

    /** Visibility instrumentation is retained as a no-op compatibility surface. */
    public static Category pushCategory(Category category) {
        return null;
    }

    public static void popCategory(Category previous) {
    }

    public static Category resolveCategory(Category fallback) {
        return fallback;
    }

    /** Visibility statistics were removed; keep callers source-compatible without collecting data. */
    public static void record(Category category, Visibility.Result result, long elapsedNanos) {
    }

    public static void recordRenderAttemptedBecausePending(Category category) {
    }

    public static void recordOutcome(Category category, boolean rendered, long elapsedNanos) {
    }

    public static void recordQueueRejected(Category category) {
    }

    public static void recordBackoff(Category category) {
    }

    public static void recordFutureCompleted(Category category) {
    }

    public static void recordFutureError(Category category) {
    }

    public static void recordLruHit() {
    }

    public static void recordLruEviction() {
    }

    public static void recordCellHintHit() {
    }

    public static void recordCellHintCapacityReject() {
    }

    public static void recordRenderDistanceReject() {
    }

    public static void recordCooperativeSubmission() {
        // Admission is counted when the submission enters the pending GPU batch.
        // Keep this compatibility hook side-effect free to avoid double counting.
    }

    public static void recordCpuFallback() {
        if (active && IVRConfig.cpuOptimization) frameCpuFallbacks++;
    }

    public static void recordGpuFallback() {
        if (active && IVRConfig.gpuOptimization) frameGpuFallbacks++;
    }

    public static void recordGpuReject() {
        recordGpuReject(GpuRejectReason.BACKEND_CONTEXT);
    }

    public static void recordGpuReject(GpuRejectReason reason) {
        if (!active || !IVRConfig.gpuOptimization) return;
        frameGpuRejects++;
        if (reason != null) frameGpuRejectReasons[reason.ordinal()]++;
    }

    public static void recordGpuAdmission() {
        if (active && IVRConfig.gpuOptimization) frameGpuAdmissions++;
    }

    /** A mesh became available to the version-specific GPU adapter. */
    public static void recordGpuMeshReady() {
        if (active && IVRConfig.gpuOptimization) frameGpuMeshReady++;
    }

    public static void recordGpuUpload() {
        if (active && IVRConfig.gpuOptimization) frameGpuUploads++;
    }

    public static void recordGpuDrawAttempt(long groups) {
        if (active && IVRConfig.gpuOptimization) frameGpuDrawAttempts += Math.max(1L, groups);
    }

    public static void recordGpuDraw(long instances) {
        if (active && IVRConfig.gpuOptimization) frameGpuDraws += Math.max(1L, instances);
    }

    public static void recordMeshCacheHit() {
        if (active && IVRConfig.cpuOptimization) frameMeshCacheHits++;
    }

    public static void recordMeshCacheMiss() {
        if (active && IVRConfig.cpuOptimization) frameMeshCacheMisses++;
    }

    public static void recordMeshCacheReject() {
        if (active && IVRConfig.cpuOptimization) frameMeshCacheRejects++;
    }

    public static Diagnostics diagnostics() {
        return diagnostics;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    /** Called only while composing the F3 text, so allocations stay off hot paths. */
    public static String[] getDebugLines() {
        List<String> lines = new ArrayList<>(4);
        Diagnostics d = diagnostics;
        if (IVRConfig.cpuOptimization) {
            lines.add("IVR cpu stats mesh hit/miss/reject "
                    + d.frameMeshCacheHits + "/" + d.frameMeshCacheMisses + "/"
                    + d.frameMeshCacheRejects + " fallback " + d.frameCpuFallbacks
                    + " time key/cap/stab/replay(us) " + d.cpuTimingLine());
        }
        if (IVRConfig.gpuOptimization) {
            lines.add("IVR gpu stats admit/ready/upload/drawTry/draw/reject/fallback "
                    + d.frameGpuAdmissions + "/" + d.frameGpuMeshReady + "/"
                    + d.frameGpuUploads + "/" + d.frameGpuDrawAttempts + "/"
                    + d.frameGpuDraws + "/" + d.frameGpuRejects + "/"
                    + d.frameGpuFallbacks + " reasons " + d.gpuRejectLine());
        }
        if (IVRConfig.cpuOptimization || IVRConfig.gpuOptimization) {
            lines.add(IVRMemoryBudget.debugLine());
        }
        return lines.toArray(new String[0]);
    }

    public record Diagnostics(long frameGpuAdmissions, long frameGpuMeshReady, long frameGpuUploads,
                              long frameGpuDrawAttempts, long frameGpuDraws,
                              long frameGpuRejects, long frameCpuFallbacks,
                              long frameGpuFallbacks,
                              long frameMeshCacheHits, long frameMeshCacheMisses,
                              long frameMeshCacheRejects, long totalGpuAdmissions,
                              long totalGpuMeshReady, long totalGpuUploads, long totalGpuDrawAttempts,
                              long totalGpuDraws, long totalGpuRejects,
                              long totalCpuFallbacks, long totalGpuFallbacks,
                              long totalMeshCacheHits,
                              long totalMeshCacheMisses, long totalMeshCacheRejects,
                              long[] cpuPhaseNanos, long[] cpuPhaseSamples,
                              long[] gpuRejectReasons) {

        private String cpuTimingLine() {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < CpuPhase.values().length; i++) {
                if (i > 0) line.append('/');
                long samples = cpuPhaseSamples()[i];
                long micros = samples == 0L ? 0L : cpuPhaseNanos()[i] / 1_000L / samples;
                line.append(micros).append('[').append(samples).append(']');
            }
            return line.toString();
        }

        private String gpuRejectLine() {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < GpuRejectReason.values().length; i++) {
                if (i > 0) line.append('/');
                line.append(GpuRejectReason.values()[i].label).append('=').append(gpuRejectReasons()[i]);
            }
            return line.toString();
        }

        private static Diagnostics empty() {
            return new Diagnostics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    new long[CpuPhase.values().length], new long[CpuPhase.values().length],
                    new long[GpuRejectReason.values().length]);
        }
    }

    /** Kept as an empty compatibility value; visibility/ray snapshots are no longer collected. */
    public static final class Snapshot {
        private Snapshot() {}

        private static Snapshot empty() {
            return new Snapshot();
        }
    }
}
