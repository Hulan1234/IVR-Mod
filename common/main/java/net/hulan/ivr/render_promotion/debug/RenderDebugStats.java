package net.hulan.ivr.render_promotion.debug;

import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.hulan.ivr.render_promotion.visibility.Visibility;

/** Low-overhead, render-thread visibility counts and per-category check timings for F3. */
public final class RenderDebugStats {

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

    private static final int CATEGORY_COUNT = Category.values().length;
    private static final long[] candidates = new long[CATEGORY_COUNT];
    private static final long[] visible = new long[CATEGORY_COUNT];
    private static final long[] culled = new long[CATEGORY_COUNT];
    private static final long[] pending = new long[CATEGORY_COUNT];
    private static final long[] queueRejected = new long[CATEGORY_COUNT];
    private static final long[] backoff = new long[CATEGORY_COUNT];
    private static final long[] futureCompleted = new long[CATEGORY_COUNT];
    private static final long[] futureErrors = new long[CATEGORY_COUNT];
    private static final long[] categoryNanos = new long[CATEGORY_COUNT];
    private static final long[] lastFrameMicros = new long[CATEGORY_COUNT];
    private static final long[] averageMicros = new long[CATEGORY_COUNT];

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile boolean active;
    private static volatile boolean overlayRequested;
    private static final ThreadLocal<Category> categoryOverride = new ThreadLocal<>();

    private RenderDebugStats() {}

    /** Called by the debug overlay; the next render frame enables collection. */
    public static void requestOverlay() {
        overlayRequested = true;
    }

    /** Advances the one-frame handoff between the overlay and the renderer. */
    public static void beginFrame() {
        active = overlayRequested;
        overlayRequested = false;
        if (!active) return;
        java.util.Arrays.fill(candidates, 0L);
        java.util.Arrays.fill(visible, 0L);
        java.util.Arrays.fill(culled, 0L);
        java.util.Arrays.fill(pending, 0L);
        java.util.Arrays.fill(queueRejected, 0L);
        java.util.Arrays.fill(backoff, 0L);
        java.util.Arrays.fill(futureCompleted, 0L);
        java.util.Arrays.fill(futureErrors, 0L);
        java.util.Arrays.fill(categoryNanos, 0L);
    }

    /** Publishes an immutable copy after the render frame has finished. */
    public static void endFrame() {
        if (!active) return;
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            long micros = Math.max(0L, categoryNanos[i] / 1_000L);
            lastFrameMicros[i] = micros;
            averageMicros[i] = averageMicros[i] == 0L
                    ? micros : (averageMicros[i] * 7L + micros) / 8L;
        }
        snapshot = new Snapshot(candidates, visible, culled, pending,
                queueRejected, backoff, futureCompleted, futureErrors, lastFrameMicros, averageMicros);
    }

    public static boolean enabled() {
        return active;
    }

    public static long startTiming() {
        return active ? System.nanoTime() : 0L;
    }

    public static long elapsedSince(long started) {
        return started == 0L ? 0L : Math.max(0L, System.nanoTime() - started);
    }

    public static Category pushCategory(Category category) {
        Category previous = categoryOverride.get();
        categoryOverride.set(category);
        return previous;
    }

    public static void popCategory(Category previous) {
        if (previous == null) categoryOverride.remove();
        else categoryOverride.set(previous);
    }

    public static Category resolveCategory(Category fallback) {
        Category override = categoryOverride.get();
        return override == null ? fallback : override;
    }

    public static void record(Category category, Visibility.Result result, long elapsedNanos) {
        if (!active || category == null) return;
        int i = category.ordinal();
        categoryNanos[i] += Math.max(0L, elapsedNanos);
        candidates[i]++;
        if (result == Visibility.Result.PENDING) pending[i]++;
        else if (result == Visibility.Result.DISTANCE || result == Visibility.Result.FRUSTUM
                || result == Visibility.Result.UNLOADED || result == Visibility.Result.OCCLUDED) culled[i]++;
        else visible[i]++;
    }

    public static void recordOutcome(Category category, boolean rendered, long elapsedNanos) {
        if (!active || category == null) return;
        int i = category.ordinal();
        categoryNanos[i] += Math.max(0L, elapsedNanos);
        candidates[i]++;
        if (rendered) visible[i]++;
        else culled[i]++;
    }

    public static void recordQueueRejected(Category category) {
        if (active && category != null) queueRejected[category.ordinal()]++;
    }

    public static void recordBackoff(Category category) {
        if (active && category != null) backoff[category.ordinal()]++;
    }

    public static void recordFutureCompleted(Category category) {
        if (active && category != null) futureCompleted[category.ordinal()]++;
    }

    public static void recordFutureError(Category category) {
        if (active && category != null) futureErrors[category.ordinal()]++;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    /** Called only while composing the F3 text, so allocations stay off hot paths. */
    public static String[] getDebugLines() {
        Snapshot s = snapshot;
        String[] lines = new String[CATEGORY_COUNT + 2];
        lines[0] = "IVR render stats (cand/visible/culled/pending, q/backoff, future ok/err, check us/avg)";
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            lines[i + 1] = s.labels[i] + " " + s.candidates[i] + "/" + s.visible[i] + "/"
                    + s.culled[i] + "/" + s.pending[i] + " q" + s.queueRejected[i] + "/" + s.backoff[i]
                    + " f" + s.futureCompleted[i] + "/" + s.futureErrors[i]
                    + " " + s.lastFrameMicros[i] + "/" + s.averageMicros[i];
        }
        lines[CATEGORY_COUNT + 1] = OcclusionScheduler.debugBudgetLine();
        return lines;
    }

    public static final class Snapshot {
        private final String[] labels;
        private final long[] candidates, visible, culled, pending, queueRejected, backoff, futureCompleted, futureErrors;
        private final long[] lastFrameMicros, averageMicros;

        private Snapshot(long[] candidates, long[] visible, long[] culled, long[] pending,
                         long[] queueRejected, long[] backoff, long[] futureCompleted, long[] futureErrors,
                         long[] lastFrameMicros, long[] averageMicros) {
            this.labels = new String[CATEGORY_COUNT];
            for (int i = 0; i < CATEGORY_COUNT; i++) this.labels[i] = Category.values()[i].label;
            this.candidates = candidates.clone();
            this.visible = visible.clone();
            this.culled = culled.clone();
            this.pending = pending.clone();
            this.queueRejected = queueRejected.clone();
            this.backoff = backoff.clone();
            this.futureCompleted = futureCompleted.clone();
            this.futureErrors = futureErrors.clone();
            this.lastFrameMicros = lastFrameMicros.clone();
            this.averageMicros = averageMicros.clone();
        }

        private static Snapshot empty() {
            return new Snapshot(new long[CATEGORY_COUNT], new long[CATEGORY_COUNT], new long[CATEGORY_COUNT],
                    new long[CATEGORY_COUNT], new long[CATEGORY_COUNT], new long[CATEGORY_COUNT],
                    new long[CATEGORY_COUNT], new long[CATEGORY_COUNT], new long[CATEGORY_COUNT],
                    new long[CATEGORY_COUNT]);
        }
    }
}
