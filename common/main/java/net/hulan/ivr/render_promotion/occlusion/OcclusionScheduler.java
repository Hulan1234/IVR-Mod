package net.hulan.ivr.render_promotion.occlusion;

import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.hulan.ivr.client.IVRConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class OcclusionScheduler {

    private static final int QUERY_KIND_COUNT = 4;
    private static final int DEFAULT_MAX_TOTAL_RAYS = 160;
    private static final int MIN_TOTAL_RAYS = 36;
    private static final int INITIAL_TOTAL_RAYS = 64;
    private static final int BUDGET_GROW_STEP = 16;
    private static final int BUDGET_SURGE_STEP = 32;
    private static final int BUDGET_SHRINK_STEP = 8;
    private static final int BUDGET_GROWTH_WINDOW = 2;
    private static final int BUDGET_IDLE_WINDOW = 3;
    private static final int[] INITIAL_RAY_BUDGET = {28, 8, 16, 12};
    private static final int[] MIN_RAY_BUDGET = new int[QUERY_KIND_COUNT];
    private static final int[] RAY_BUDGET = new int[QUERY_KIND_COUNT];
    private static int budgetTotal = INITIAL_TOTAL_RAYS;
    private static int backlogStreak;
    private static int idleStreak;
    private static int allocationCursor;
    private static long frameDrainNanos;
    private static int frameFutureCompleted;
    private static int frameFutureErrors;
    private static int frameQueueRejected;
    private static int frameBackoff;
    private static final int[] QUEUE_LIMIT = {2048, 256, 256, 512};
    private static final int[] PRIORITY_QUEUE_LIMIT = {512, 256, 256, 256};
    private static final long DRAIN_TIME_BUDGET_NS = 1_000_000L;
    /** A completed query may be reused for up to four render frames, including its completion frame. */
    private static final long MAX_RESULT_REUSE_FRAMES = 4L;
    private static final int SAMPLE_COUNT = 27;
    private static final int TRAIN_SAMPLE_COUNT = 14;
    private static final double SMALL_CAMERA_MOVE_SQR = 0.75D * 0.75D;
    private static final float SMALL_CAMERA_ROTATION = 1.0E-4F;
    private static final long OCCLUDED_TTL_NS = 12_000_000_000L;
    private static final long VISIBLE_TTL_NS = 8_000_000_000L;
    private static final long OCCLUDED_RECHECK_GRACE_NS = 250_000_000L;
    private static final int RESULT_CACHE_LIMIT = 4096;
    private static final int CELL_HINT_LIMIT = 256;
    private static final long CELL_SIZE = 2L;
    private static final int MAX_BOX_REQUESTS_PER_FRAME = 24;
    private static final int MAX_EYECANDY_REQUESTS_PER_FRAME = 32;
    private static final Map<Object, Request>[] REQUESTS = queues();
    private static final PriorityQueue<PriorityEntry>[] PRIORITY_QUEUES = priorityQueues();
    private static final int BE_RECHECK_LIMIT = 256;
    private static final int BE_RECHECK_RESERVED_RAYS = 8;
    private static final int BE_RECHECK_BACKLOG_RAYS = 4;
    private static final Map<Object, Request> BE_RECHECKS = new LinkedHashMap<>();
    private static final Map<Object, Result> RESULTS = new ResultCache(RESULT_CACHE_LIMIT);
    private static final Map<CellHintKey, CellHint> CELL_HINTS =
            new LinkedHashMap<>(CELL_HINT_LIMIT, 0.75F, true);
    private static Level world;
    private static Vec3 frameCamera;
    private static FrustumCache.CameraSnapshot frameView;
    private static long cellRevision = -1L;
    private static long blockEpoch;
    private static long frameId = -1;
    private static long frameDrainDeadline;
    private static int boxRequests;
    private static int eyeCandyRequests;
    private static boolean beReplacementAttempted;
    private static long prioritySequence;
    private static final int[] frameRays = new int[QUERY_KIND_COUNT];

    private static final int EYE_CANDY_KIND = 3;

    static {
        int[] minimumBudget = {16, 4, 8, 8};
        System.arraycopy(INITIAL_RAY_BUDGET, 0, RAY_BUDGET, 0, QUERY_KIND_COUNT);
        System.arraycopy(minimumBudget, 0, MIN_RAY_BUDGET, 0, QUERY_KIND_COUNT);
        int initialTotal = 0;
        int minimumTotal = 0;
        for (int kind = 0; kind < QUERY_KIND_COUNT; kind++) {
            initialTotal += INITIAL_RAY_BUDGET[kind];
            minimumTotal += MIN_RAY_BUDGET[kind];
            if (INITIAL_RAY_BUDGET[kind] < MIN_RAY_BUDGET[kind]) {
                throw new IllegalStateException("Initial occlusion budget is below a category minimum");
            }
        }
        if (REQUESTS.length != QUERY_KIND_COUNT) {
            throw new IllegalStateException("Occlusion query kind arrays are inconsistent");
        }
        if (initialTotal != INITIAL_TOTAL_RAYS || minimumTotal != MIN_TOTAL_RAYS) {
            throw new IllegalStateException("Occlusion total and category budgets are inconsistent");
        }
    }

    public static synchronized void shutdown() {
        for (Map<Object, Request> queue : REQUESTS) {
            cancelRequests(queue);
            queue.clear();
        }
        for (PriorityQueue<PriorityEntry> queue : PRIORITY_QUEUES) queue.clear();
        prioritySequence = 0L;
        cancelRequests(BE_RECHECKS);
        BE_RECHECKS.clear();
        RESULTS.clear();
        CELL_HINTS.clear();
        world = null;
        frameCamera = null;
        frameView = null;
        cellRevision = -1L;
        blockEpoch = 0L;
        frameId = -1L;
        frameDrainDeadline = 0L;
        budgetTotal = Math.min(INITIAL_TOTAL_RAYS, maxTotalRays());
        backlogStreak = idleStreak = allocationCursor = 0;
        frameDrainNanos = 0L;
        frameFutureCompleted = frameFutureErrors = frameQueueRejected = frameBackoff = 0;
        System.arraycopy(INITIAL_RAY_BUDGET, 0, RAY_BUDGET, 0, QUERY_KIND_COUNT);
        OcclusionRaycaster.shutdown();
    }

    private static Map<Object, Request>[] queues() {
        Map<Object, Request>[] queues = new Map[QUERY_KIND_COUNT];
        for (int kind = 0; kind < queues.length; kind++) queues[kind] = new LinkedHashMap<>();
        return queues;
    }

    @SuppressWarnings("unchecked")
    private static PriorityQueue<PriorityEntry>[] priorityQueues() {
        PriorityQueue<PriorityEntry>[] queues = new PriorityQueue[QUERY_KIND_COUNT];
        for (int kind = 0; kind < queues.length; kind++) {
            queues[kind] = new PriorityQueue<>((first, second) -> {
                int sequence = Long.compare(first.sequence, second.sequence);
                if (sequence != 0) return sequence;
                return Double.compare(second.priority, first.priority);
            });
        }
        return queues;
    }

    public enum ResultType { VISIBLE, OCCLUDED, PENDING, DEFERRED, UNLOADED }

    public record QueryResult(ResultType type, BlockPos witness) {
        /** Unknown work remains renderable; only a completed occlusion proof can hide it. */
        public boolean mayRender() { return type == ResultType.VISIBLE || type == ResultType.PENDING; }
    }

    private static final QueryResult VISIBLE = new QueryResult(ResultType.VISIBLE, null);
    private static final QueryResult OCCLUDED = new QueryResult(ResultType.OCCLUDED, null);
    private static final QueryResult PENDING = new QueryResult(ResultType.PENDING, null);
    private static final QueryResult DEFERRED = new QueryResult(ResultType.DEFERRED, null);
    private static final QueryResult UNLOADED = new QueryResult(ResultType.UNLOADED, null);

    public static Object boxKey(AABB bounds) {
        return new BoxKey(Math.round(bounds.minX * 16.0D), Math.round(bounds.minY * 16.0D),
                Math.round(bounds.minZ * 16.0D), Math.round(bounds.maxX * 16.0D),
                Math.round(bounds.maxY * 16.0D), Math.round(bounds.maxZ * 16.0D));
    }

    public static Object eyeCandyKey(BlockPos pos) {
        return new EyeCandyKey(pos == null ? 0L : pos.asLong());
    }

    public static synchronized QueryResult query(Level currentWorld, Object key, AABB bounds, BlockPos ignoredPos) {
        return queryInternal(currentWorld, key, bounds, ignoredPos, null, -1, true);
    }

    public static synchronized QueryResult queryTrain(Level currentWorld, Object key, AABB bounds, BlockPos ignoredPos) {
        // Train admission participates in the deferred path when this frame's
        // queue or budget cannot accept the request. An admitted but unfinished
        // query still returns PENDING so the train remains renderable.
        return queryInternal(currentWorld, key, bounds, ignoredPos, null, 1, true);
    }

    public static synchronized void forgetTrainQuery(Object key) {
        if (key == null) return;
        Request request = REQUESTS[1].remove(key);
        if (request != null) cancelRequest(request);
        PRIORITY_QUEUES[1].removeIf(entry -> key.equals(entry.key));
        RESULTS.remove(key);
    }

    public static synchronized QueryResult queryEyeCandy(Level currentWorld, Object key, AABB bounds,
                                                         BlockPos ignoredPos) {
        return queryInternal(currentWorld, key, bounds, ignoredPos, null, EYE_CANDY_KIND, true);
    }

    public static synchronized QueryResult cachedBox(Level currentWorld, AABB bounds) {
        if (currentWorld == null || bounds == null) return PENDING;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(currentWorld);
        Vec3 camera = view.cameraPosition();
        begin(currentWorld, view);
        Result result = RESULTS.get(boxKey(bounds));
        boolean boxWitnessValid = result == null || !result.occluded || !result.cameraMoved(camera)
                || result.revalidateBoxWitness(currentWorld, camera, frameId);
        if (result == null || !result.matches(currentWorld, view, bounds, null)
                || !boxWitnessValid || !result.validateNextBlocker(currentWorld, frameId)
                || !result.fresh(System.nanoTime())) {
            if (result != null && result.occluded && !boxWitnessValid) RESULTS.remove(boxKey(bounds));
            if (result != null && !result.fresh(System.nanoTime())) RESULTS.remove(boxKey(bounds));
            return PENDING;
        }
        RenderDebugStats.recordLruHit();
        return result.occluded ? OCCLUDED : VISIBLE;
    }

    public static synchronized QueryResult query(Level currentWorld,
                                    Object key,
                                    AABB bounds,
                                    BlockPos ignoredPos,
                                    BlockEntity target) {
        return queryInternal(currentWorld, key, bounds, ignoredPos, target, -1, true);
    }

    @SuppressWarnings("deprecation")
    private static QueryResult queryInternal(Level currentWorld,
                                             Object key,
                                             AABB bounds,
                                             BlockPos ignoredPos,
                                             BlockEntity target,
                                             int forcedKind,
                                             boolean allowDeferred) {
        if (currentWorld == null || key == null || bounds == null) return PENDING;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(currentWorld);
        Vec3 camera = view.cameraPosition();
        begin(currentWorld, view);
        int kind = forcedKind >= 0 ? forcedKind : target != null ? 0 : key instanceof BoxKey ? 2 : 1;
        CellHintKey hintKey = cellHintKey(kind, bounds);
        if (kind == 1 && !validTrainBounds(bounds)) {
            Request invalidRequest = REQUESTS[1].get(key);
            if (invalidRequest != null) cancelRequest(invalidRequest);
            REQUESTS[1].remove(key);
            RESULTS.remove(key);
            return PENDING;
        }
        long now = System.nanoTime();
        Result result = RESULTS.get(key);
        boolean boxWitnessValid = kind != 2 || result == null || !result.occluded
                || !result.cameraMoved(camera) || result.revalidateBoxWitness(currentWorld, camera, frameId);
        if (result != null && result.matches(currentWorld, view, bounds, target)
                && boxWitnessValid
                && result.validateNextBlocker(currentWorld, frameId)
                && result.fresh(now)) {
            RenderDebugStats.recordLruHit();
            return result.occluded ? new QueryResult(ResultType.OCCLUDED,
                    kind != 2 ? result.blockers[0] : null) : VISIBLE;
        }
        if (kind == 2 && result != null && result.occluded && !boxWitnessValid) {
            RESULTS.remove(key);
            result = null;
        }
        if (result != null && !result.blockersValid) {
            RESULTS.remove(key);
            result = null;
        }
        boolean recheckingOccluded = kind == 0 && result != null
                && result.matchesTarget(currentWorld, view, bounds, target)
                && result.occluded
                && result.camera.distanceToSqr(camera) <= SMALL_CAMERA_MOVE_SQR
                && result.validateNextBlocker(currentWorld, frameId)
                && result.movementGrace(now);
        if (result != null && (!result.blockersValid || !recheckingOccluded)) {
            RESULTS.remove(key);
            if (!result.blockersValid) recheckingOccluded = false;
        }
        if (kind == 0 && !recheckingOccluded) {
            Request previousRecheck = BE_RECHECKS.remove(key);
            if (previousRecheck != null && previousRecheck.matches(currentWorld, camera, bounds, target)
                    && REQUESTS[0].size() < QUEUE_LIMIT[0]) {
                REQUESTS[0].put(key, previousRecheck);
            }
        }
        Map<Object, Request> queue = recheckingOccluded ? BE_RECHECKS : REQUESTS[kind];
        Request request = queue.get(key);
        if (request != null && !request.matches(currentWorld, camera, bounds, target)) {
            clearCellHint(key, request);
            queue.remove(key);
            cancelRequest(request);
            request = null;
        }
        if (request == null) {
            if (shouldDeferForCellHint(currentWorld, hintKey)) {
                return admission(allowDeferred);
            }
            if (queue.size() >= (recheckingOccluded ? BE_RECHECK_LIMIT : QUEUE_LIMIT[kind])) {
                if (!recheckingOccluded && kind == 0 && evictLowestPriority(queue, currentWorld, bounds)) {
                } else {
                    frameQueueRejected++;
                    RenderDebugStats.recordQueueRejected(statsCategory(kind));
                    if (recheckingOccluded && result.overflowGrace(now, key)) return OCCLUDED;
                    RESULTS.remove(key);
                    return admission(allowDeferred);
                }
            }
            if (kind == 2 && boxRequests >= MAX_BOX_REQUESTS_PER_FRAME) {
                frameQueueRejected++;
                RenderDebugStats.recordQueueRejected(statsCategory(kind));
                return admission(allowDeferred);
            }
            if (kind == 2) boxRequests++;
            if (kind == EYE_CANDY_KIND && eyeCandyRequests >= MAX_EYECANDY_REQUESTS_PER_FRAME) {
                frameQueueRejected++;
                RenderDebugStats.recordQueueRejected(statsCategory(kind));
                return admission(allowDeferred);
            }
            if (kind == EYE_CANDY_KIND) eyeCandyRequests++;
            request = new Request(currentWorld, camera, bounds, ignoredPos, target,
                    kind == 1, kind == EYE_CANDY_KIND);
            queue.put(key, request);
            enqueuePriority(kind, key, request);
            if (hintKey != null) {
                CELL_HINTS.put(hintKey,
                        new CellHint(currentWorld, frameView, frameId, cellRevision,
                                blockEpoch, key, request));
            }
        }
        drain(kind, currentWorld);
        if (request.unloadedPos != null) {
            // The request was admitted already; waiting for its sample chunk is
            // unresolved visibility, not an admission rejection.
            if (!currentWorld.hasChunkAt(request.unloadedPos)) return PENDING;
            request.unloadedPos = null;
            request.retryAfterFrame = frameId;
        }
        result = RESULTS.get(key);
        boxWitnessValid = kind != 2 || result == null || !result.occluded
                || !result.cameraMoved(camera) || result.revalidateBoxWitness(currentWorld, camera, frameId);
        if (result != null && result.matches(currentWorld, view, bounds, target)
                && boxWitnessValid
                && result.validateNextBlocker(currentWorld, frameId)
                && result.fresh(System.nanoTime())) {
            RenderDebugStats.recordLruHit();
            return result.occluded ? new QueryResult(ResultType.OCCLUDED,
                    kind != 2 ? result.blockers[0] : null) : VISIBLE;
        }
        if (kind == 2 && result != null && result.occluded && !boxWitnessValid) {
            RESULTS.remove(key);
            result = null;
        }
        if (result != null && !result.blockersValid) {
            RESULTS.remove(key);
            BE_RECHECKS.remove(key);
            return PENDING;
        }
        if (recheckingOccluded && BE_RECHECKS.containsKey(key)) return OCCLUDED;
        return PENDING;
    }

    private static QueryResult admission(boolean allowDeferred) {
        // Only callers that explicitly opt into deferred admission use this
        // result. Once a request exists, unresolved work is always PENDING.
        return allowDeferred ? DEFERRED : PENDING;
    }

    private static CellHintKey cellHintKey(int kind, AABB bounds) {
        if (kind == 1 || bounds == null
                || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY)
                || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxX)
                || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)
                || !(bounds.minX < bounds.maxX) || !(bounds.minY < bounds.maxY)
                || !(bounds.minZ < bounds.maxZ)) {
            return null;
        }
        long minX = floorCellCoordinate(bounds.minX);
        long minY = floorCellCoordinate(bounds.minY);
        long minZ = floorCellCoordinate(bounds.minZ);
        long maxX = floorCellCoordinate(Math.nextDown(bounds.maxX));
        long maxY = floorCellCoordinate(Math.nextDown(bounds.maxY));
        long maxZ = floorCellCoordinate(Math.nextDown(bounds.maxZ));
        if (minX == Long.MIN_VALUE || minY == Long.MIN_VALUE || minZ == Long.MIN_VALUE
                || maxX == Long.MIN_VALUE || maxY == Long.MIN_VALUE || maxZ == Long.MIN_VALUE) {
            return null;
        }
        long cellX = Math.floorDiv(minX, CELL_SIZE);
        long cellY = Math.floorDiv(minY, CELL_SIZE);
        long cellZ = Math.floorDiv(minZ, CELL_SIZE);
        return Math.floorDiv(maxX, CELL_SIZE) == cellX
                && Math.floorDiv(maxY, CELL_SIZE) == cellY
                && Math.floorDiv(maxZ, CELL_SIZE) == cellZ
                ? new CellHintKey(kind, cellX, cellY, cellZ) : null;
    }
    private static long floorCellCoordinate(double coordinate) {
        double floor = Math.floor(coordinate);
        return !Double.isFinite(floor) || floor <= Long.MIN_VALUE || floor >= Long.MAX_VALUE
                ? Long.MIN_VALUE : (long) floor;
    }
    private static boolean shouldDeferForCellHint(Level currentWorld, CellHintKey key) {
        if (key == null) return false;
        purgeCellHints();
        CellHint hint = CELL_HINTS.get(key);
        if (hint != null && hint.matches(currentWorld, frameView, frameId, cellRevision, blockEpoch)
                && isCellHintActive(hint)) {
            RenderDebugStats.recordCellHintHit();
            return true;
        }
        if (hint != null) CELL_HINTS.remove(key);
        boolean capacityRejected = CELL_HINTS.size() >= CELL_HINT_LIMIT;
        if (capacityRejected) RenderDebugStats.recordCellHintCapacityReject();
        return capacityRejected;
    }
    private static void purgeCellHints() {
        CELL_HINTS.entrySet().removeIf(entry -> {
            CellHint hint = entry.getValue();
            return !hint.matches(world, frameView, frameId, cellRevision, blockEpoch)
                    || !isCellHintActive(hint);
        });
    }
    private static boolean isCellHintActive(CellHint hint) {
        if (hint == null || hint.request == null || hint.sourceKey == null) return false;
        for (Map<Object, Request> queue : REQUESTS) {
            if (queue.get(hint.sourceKey) == hint.request) return true;
        }
        return BE_RECHECKS.get(hint.sourceKey) == hint.request;
    }
    private static void clearCellHint(Object sourceKey, Request request) {
        if (request == null) return;
        CELL_HINTS.entrySet().removeIf(entry -> {
            CellHint hint = entry.getValue();
            return hint.request == request
                    && (sourceKey == null || sourceKey.equals(hint.sourceKey));
        });
    }
    private static void begin(Level currentWorld, FrustumCache.CameraSnapshot view) {
        Vec3 camera = view == null ? null : view.cameraPosition();
        if (world != currentWorld && Minecraft.getInstance().level == currentWorld) {
            OcclusionRaycaster.start();
        } else {
            OcclusionRaycaster.ensureRunning();
        }
        boolean worldChanged = world != currentWorld;
        boolean cameraMoved = !worldChanged && significantCameraMove(frameCamera, camera);
        if (worldChanged) {
            for (Map<Object, Request> queue : REQUESTS) {
                cancelRequests(queue);
                queue.clear();
            }
            cancelRequests(BE_RECHECKS);
            BE_RECHECKS.clear();
            for (PriorityQueue<PriorityEntry> queue : PRIORITY_QUEUES) queue.clear();
            for (Map<Object, Request> queue : REQUESTS) {
                for (Request request : queue.values()) request.priorityQueued = false;
            }
            RESULTS.clear();
            CELL_HINTS.clear();
            blockEpoch++;
            world = currentWorld;
        } else if (cameraMoved) {
            // A large camera jump invalidates every request's ray origin. Cancel futures
            // before clearing the queues so their completions cannot publish old results.
            for (Map<Object, Request> queue : REQUESTS) {
                cancelRequests(queue);
                queue.clear();
            }
            cancelRequests(BE_RECHECKS);
            BE_RECHECKS.clear();
            for (PriorityQueue<PriorityEntry> queue : PRIORITY_QUEUES) queue.clear();
            RESULTS.clear();
            CELL_HINTS.clear();
        }
        long currentRevision = RenderDataRevision.current();
        if (frameView != view || cellRevision != currentRevision) {
            CELL_HINTS.clear();
        }
        frameView = view;
        cellRevision = currentRevision;
        frameCamera = camera;
        long currentFrame = FrustumCache.frameId();
        if (frameId != currentFrame) {
            if (frameId >= 0L) adjustBudget();
            frameId = currentFrame;
            frameDrainDeadline = System.nanoTime() + DRAIN_TIME_BUDGET_NS;
            boxRequests = 0;
            eyeCandyRequests = 0;
            beReplacementAttempted = false;
            Arrays.fill(frameRays, 0);
            frameDrainNanos = 0L;
            frameFutureCompleted = frameFutureErrors = frameQueueRejected = frameBackoff = 0;
            CELL_HINTS.clear();
            purgeExpiredResults(System.nanoTime());
        }
    }

    private static boolean significantCameraMove(Vec3 previous, Vec3 current) {
        return previous != null && current != null
                && previous.distanceToSqr(current) > SMALL_CAMERA_MOVE_SQR;
    }

    private static void purgeExpiredResults(long now) {
        RESULTS.entrySet().removeIf(entry -> entry == null || entry.getValue() == null
                || !entry.getValue().fresh(now));
    }

    private static void drain(int kind, Level currentWorld) {
        if (frameRays[kind] >= RAY_BUDGET[kind]) return;
        long started = System.nanoTime();
        long deadline = frameDrainDeadline;
        if (kind == 0) {
            int recheckLimit = REQUESTS[0].isEmpty()
                    ? BE_RECHECK_RESERVED_RAYS : BE_RECHECK_BACKLOG_RAYS;
            drainQueue(BE_RECHECKS, kind, currentWorld, recheckLimit, deadline);
            drainQueue(REQUESTS[kind], kind, currentWorld, RAY_BUDGET[kind], deadline);
            drainQueue(BE_RECHECKS, kind, currentWorld, RAY_BUDGET[kind], deadline);
        } else {
            drainQueue(REQUESTS[kind], kind, currentWorld, RAY_BUDGET[kind], deadline);
        }
        frameDrainNanos += Math.max(0L, System.nanoTime() - started);
    }

    private static void drainQueue(Map<Object, Request> queue, int kind, Level currentWorld,
                                   int limit, long deadline) {
        while (!queue.isEmpty() && frameRays[kind] < limit && System.nanoTime() < deadline) {
            Map.Entry<Object, Request> entry = nextRequest(queue, kind, currentWorld);
            if (entry == null) break;
            Request request = entry.getValue();
            request.retarget(frameCamera);
            if (queue == BE_RECHECKS) {
                Result previous = RESULTS.get(entry.getKey());
                if (previous == null || !previous.blockersValid
                        || !previous.validateNextBlocker(currentWorld, frameId)
                        || !previous.movementGrace(System.nanoTime())
                        || previous.camera.distanceToSqr(frameCamera) > SMALL_CAMERA_MOVE_SQR) {
                    if (previous != null && !previous.blockersValid) RESULTS.remove(entry.getKey());
                    clearCellHint(entry.getKey(), request);
                    queue.remove(entry.getKey());
                    BlockEntity recheckTarget = request.target == null ? null : request.target.get();
                    if (previous != null && previous.blockersValid
                            && previous.matches(currentWorld, frameView, request.bounds, recheckTarget)
                            && request.matches(currentWorld, frameCamera, request.bounds, recheckTarget)
                            && REQUESTS[0].size() < QUEUE_LIMIT[0]) {
                        REQUESTS[0].putIfAbsent(entry.getKey(), request);
                    }
                    continue;
                }
            }
            BlockEntity target = request.target == null ? null : request.target.get();
            if ((request.target != null && target == null)
                    || request.world != currentWorld
                    || !request.matches(currentWorld, frameCamera, request.bounds, target)) {
                clearCellHint(entry.getKey(), request);
                queue.remove(entry.getKey());
                cancelRequest(request);
                continue;
            }
            if (request.retryAfterFrame > frameId) continue;
            boolean visible = false;

            Future<OcclusionRaycaster.TraceResult> completedFuture = request.inFlight;
            int completedSample = request.inFlightSample;
            long completedGeneration = request.inFlightGeneration;
            if (completedFuture != null) {
                if (!completedFuture.isDone()) continue;
                OcclusionRaycaster.TraceResult sight;
                boolean futureError = false;
                try {
                    sight = completedFuture.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (ExecutionException failure) {
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (CancellationException cancelled) {
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (RuntimeException | LinkageError failure) {
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                }
                if (futureError) {
                    frameFutureErrors++;
                    RenderDebugStats.recordFutureError(statsCategory(request));
                } else {
                    frameFutureCompleted++;
                    RenderDebugStats.recordFutureCompleted(statsCategory(request));
                }
                if (sight == null || sight.sight() == null) {
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                }
                boolean currentFuture = request.inFlight == completedFuture;
                if (currentFuture) {
                    request.inFlight = null;
                    request.inFlightSample = -1;
                    request.inFlightGeneration = -1L;
                }
                if (!currentFuture || completedGeneration != request.generation) {
                    request.nextSample = Math.max(0, Math.min(request.nextSample, completedSample));
                    request.retryAfterFrame = frameId + 1;
                    continue;
                }
                int sampleIndex = completedSample;
                if (sampleIndex < 0 || sampleIndex >= request.blockers.length) {
                    request.nextSample = 0;
                    request.retryAfterFrame = frameId + 1;
                    continue;
                }
                if (sight.sight() == OcclusionRaycaster.Sight.UNLOADED) {
                    frameBackoff++;
                    RenderDebugStats.recordBackoff(statsCategory(request));
                    request.nextSample = sampleIndex;
                    request.unloadedPos = sight.blocker();
                    request.retryAfterFrame = frameId + 1;
                    continue;
                }
                if (sight.sight() == OcclusionRaycaster.Sight.UNKNOWN) {
                    frameBackoff++;
                    RenderDebugStats.recordBackoff(statsCategory(request));
                    request.nextSample = sampleIndex;
                    request.unknownRetries = Math.min(request.unknownRetries + 1, 6);
                    request.retryAfterFrame = frameId + (1L << request.unknownRetries);
                    continue;
                }
                request.unknownRetries = 0;
                if (sight.sight() == OcclusionRaycaster.Sight.CLEAR) {
                    visible = true;
                } else {
                    request.blockers[sampleIndex] = sight.blocker();
                    request.blockerStates[sampleIndex] = sight.blocker() == null
                            ? null : currentWorld.getBlockState(sight.blocker());
                }
            }

            if (visible || request.nextSample == request.sampleCount) {
                clearCellHint(entry.getKey(), request);
                queue.remove(entry.getKey());
                RESULTS.put(entry.getKey(), new Result(currentWorld, frameCamera, frameView,
                        request.bounds, request.target, request, request.generation,
                        !visible, System.nanoTime(),
                        request.blockers, request.blockerStates));
                continue;
            }

            while (request.nextSample < request.sampleCount && frameRays[kind] < limit
                    && System.nanoTime() < deadline) {
                int sampleIndex = request.nextSample++;
                if ((request.sampleMask & (1 << sampleIndex)) == 0) continue;
                Vec3 sample = request.sample(sampleIndex);
                frameRays[kind]++;
                int maxSteps = request.trainRay ? 1024 : 256;
                OcclusionRaycaster.RaySnapshot snapshot = OcclusionRaycaster.prepare(
                        currentWorld, frameCamera, sample, request.ignoredPos, maxSteps, deadline);
                if (snapshot.deadlineExceeded()) {
                    request.nextSample = sampleIndex;
                    request.unknownRetries = 0;
                    request.retryAfterFrame = frameId + 1;
                    moveToQueueTail(queue, entry.getKey());
                    break;
                }
                OcclusionRaycaster.TraceResult sight;
                if (snapshot.immediate() == null) {
                    Future<OcclusionRaycaster.TraceResult> future = OcclusionRaycaster.submit(snapshot);
                    if (future == null) {
                        frameQueueRejected++;
                        frameBackoff++;
                        RenderDebugStats.recordQueueRejected(statsCategory(request));
                        RenderDebugStats.recordBackoff(statsCategory(request));
                        request.nextSample = sampleIndex;
                        request.unknownRetries = Math.min(request.unknownRetries + 1, 6);
                        request.retryAfterFrame = frameId + (1L << request.unknownRetries);
                        break;
                    }
                    request.inFlight = future;
                    request.inFlightSample = sampleIndex;
                    request.inFlightGeneration = ++request.generation;
                    break;
                }
                sight = OcclusionRaycaster.resolve(snapshot);
                if (sight.sight() == OcclusionRaycaster.Sight.UNLOADED) {
                    frameBackoff++;
                    RenderDebugStats.recordBackoff(statsCategory(request));
                    request.nextSample--;
                    request.unloadedPos = sight.blocker();
                    request.retryAfterFrame = frameId + 1;
                    break;
                }
                if (sight.sight() == OcclusionRaycaster.Sight.UNKNOWN) {
                    frameBackoff++;
                    RenderDebugStats.recordBackoff(statsCategory(request));
                    request.nextSample--;
                    request.unknownRetries = Math.min(request.unknownRetries + 1, 6);
                    request.retryAfterFrame = frameId + (1L << request.unknownRetries);
                    break;
                }
                request.unknownRetries = 0;
                if (sight.sight() == OcclusionRaycaster.Sight.CLEAR) {
                    visible = true;
                    break;
                }
                BlockPos blocker = sight.blocker();
                request.blockers[sampleIndex] = blocker;
                request.blockerStates[sampleIndex] = blocker == null ? null : currentWorld.getBlockState(blocker);
            }
            if (visible || (request.inFlight == null && request.nextSample == request.sampleCount)) {
                clearCellHint(entry.getKey(), request);
                queue.remove(entry.getKey());
                RESULTS.put(entry.getKey(), new Result(currentWorld, frameCamera, frameView,
                        request.bounds, request.target, request, request.generation,
                        !visible, System.nanoTime(),
                        request.blockers, request.blockerStates));
            }
        }
    }

    private static Map.Entry<Object, Request> nextRequest(Map<Object, Request> queue,
                                                          int kind, Level currentWorld) {
        if (kind != 0 && kind != 1) {
            PriorityQueue<PriorityEntry> pending = PRIORITY_QUEUES[kind];
            while (!pending.isEmpty()) {
                PriorityEntry candidate = pending.poll();
                Request request = queue.get(candidate.key);
                if (request != candidate.request) continue;
                request.priorityQueued = false;
                if (request.retryAfterFrame > frameId
                        || request.inFlight != null && !request.inFlight.isDone()) continue;
                return Map.entry(candidate.key, request);
            }
            for (Map.Entry<Object, Request> entry : queue.entrySet()) {
                if (pending.size() >= PRIORITY_QUEUE_LIMIT[kind]) break;
                if (!entry.getValue().priorityQueued) enqueuePriority(kind, entry.getKey(), entry.getValue());
            }
            while (!pending.isEmpty()) {
                PriorityEntry candidate = pending.poll();
                Request request = queue.get(candidate.key);
                if (request != candidate.request) continue;
                request.priorityQueued = false;
                if (request.retryAfterFrame > frameId
                        || request.inFlight != null && !request.inFlight.isDone()) continue;
                return Map.entry(candidate.key, request);
            }
            return null;
        }
        FrustumCache.CameraSnapshot view = kind == 0
                ? FrustumCache.capture(currentWorld) : null;
        Map.Entry<Object, Request> best = null;
        double bestScore = -1.0D;
        for (Map.Entry<Object, Request> entry : queue.entrySet()) {
            Request request = entry.getValue();
            if (request.retryAfterFrame > frameId) continue;
            if (request.inFlight != null && !request.inFlight.isDone()) continue;
            if (kind != 0) return entry;
            if (request.priorityFrame != frameId) {
                request.inView = !view.outsideFrustum(request.bounds);
                request.priorityFrame = frameId;
            }
            double score = request.renderCost + Math.max(0L, frameId - request.enqueuedFrame) * 0.25D;
            score += request.inView ? 16.0D : 0.0D;
            if (best == null || score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return best;
    }

    private static void enqueuePriority(int kind, Object key, Request request) {
        if (kind < 0 || kind >= QUERY_KIND_COUNT || kind == 1 || request == null || request.priorityQueued
                || PRIORITY_QUEUES[kind].size() >= PRIORITY_QUEUE_LIMIT[kind]) return;
        request.priorityQueued = true;
        double priority = request.renderCost + (kind == EYE_CANDY_KIND ? 8.0D : 12.0D);
        PRIORITY_QUEUES[kind].offer(new PriorityEntry(key, request, priority, prioritySequence++));
    }

    private static void moveToQueueTail(Map<Object, Request> queue, Object key) {
        Request removed = queue.remove(key);
        if (removed != null) queue.put(key, removed);
    }

    private static RenderDebugStats.Category statsCategory(int kind) {
        RenderDebugStats.Category fallback = kind == 0 ? RenderDebugStats.Category.BE
                : kind == 1 ? RenderDebugStats.Category.TRAIN
                : kind == EYE_CANDY_KIND ? RenderDebugStats.Category.EYE_CANDY
                : RenderDebugStats.Category.RAILBOX;
        return RenderDebugStats.resolveCategory(fallback);
    }

    private static RenderDebugStats.Category statsCategory(Request request) {
        return request.statsCategory;
    }

    private static void adjustBudget() {
        int queued = BE_RECHECKS.size();
        for (Map<Object, Request> requestQueue : REQUESTS) queued += requestQueue.size();
        boolean queuePressure = frameQueueRejected > 0;
        boolean backoffPressure = frameBackoff >= 4;
        boolean saturationPressure = queuePressure || backoffPressure;
        boolean futurePressure = frameFutureErrors > frameFutureCompleted
                || (queued > 0 && frameFutureCompleted == 0 && frameDrainNanos > 1_000_000L);
        boolean slow = frameDrainNanos > 3_000_000L;
        boolean backlogPressure = queued > budgetTotal;
        boolean severeBacklog = queued > budgetTotal * 2;
        if (slow || futurePressure || saturationPressure) {
            budgetTotal = Math.max(MIN_TOTAL_RAYS, budgetTotal - BUDGET_SHRINK_STEP);
            backlogStreak = 0;
            idleStreak = 0;
        } else if (severeBacklog) {
            budgetTotal = Math.min(maxTotalRays(), budgetTotal + BUDGET_SURGE_STEP);
            backlogStreak = 0;
            idleStreak = 0;
        } else if (backlogPressure) {
            backlogStreak++;
            idleStreak = 0;
            if (backlogStreak >= BUDGET_GROWTH_WINDOW) {
                budgetTotal = Math.min(maxTotalRays(), budgetTotal + BUDGET_GROW_STEP);
                backlogStreak = 0;
            }
        } else if (queued == 0 && frameFutureErrors == 0 && frameDrainNanos < 1_000_000L) {
            idleStreak++;
            backlogStreak = 0;
            if (idleStreak >= BUDGET_IDLE_WINDOW) {
                budgetTotal = Math.max(MIN_TOTAL_RAYS, budgetTotal - BUDGET_SHRINK_STEP);
                idleStreak = 0;
            }
        } else {
            backlogStreak = 0;
            idleStreak = 0;
        }
        allocateBudget();
    }

    private static void allocateBudget() {
        budgetTotal = Math.max(MIN_TOTAL_RAYS, Math.min(maxTotalRays(), budgetTotal));
        int remaining = budgetTotal;
        for (int minimum : MIN_RAY_BUDGET) remaining -= minimum;
        System.arraycopy(MIN_RAY_BUDGET, 0, RAY_BUDGET, 0, RAY_BUDGET.length);
        int[] backlog = {REQUESTS[0].size() + BE_RECHECKS.size(), REQUESTS[1].size(),
                REQUESTS[2].size(), REQUESTS[EYE_CANDY_KIND].size()};
        while (remaining-- > 0) {
            boolean hasBacklog = false;
            for (int demand : backlog) hasBacklog |= demand > 0;
            int selected = -1;
            int lowestExtra = Integer.MAX_VALUE;
            double best = -1.0D;
            for (int offset = 0; offset < RAY_BUDGET.length; offset++) {
                int i = (allocationCursor + offset) % RAY_BUDGET.length;
                if (hasBacklog && backlog[i] == 0) continue;
                int extra = RAY_BUDGET[i] - MIN_RAY_BUDGET[i];
                double demand = backlog[i] > 0 ? backlog[i]
                        : i == 0 ? 5.0D : i == 1 ? 2.0D : i == 2 ? 3.0D : 2.5D;
                double priority = i == 0 ? 1.5D : i == 2 ? 1.25D : i == EYE_CANDY_KIND ? 1.2D : 1.0D;
                double score = demand * priority / (double) (RAY_BUDGET[i] + 1);
                if (extra < lowestExtra || extra == lowestExtra && score > best) {
                    lowestExtra = extra;
                    best = score;
                    selected = i;
                }
            }
            if (selected < 0) break;
            RAY_BUDGET[selected]++;
            allocationCursor = (selected + 1) % RAY_BUDGET.length;
        }
    }

    private static int maxTotalRays() {
        return Math.max(MIN_TOTAL_RAYS,
                Math.min(DEFAULT_MAX_TOTAL_RAYS, IVRConfig.maxOcclusionRays));
    }

    private static boolean evictLowestPriority(Map<Object, Request> queue, Level currentWorld,
                                               AABB newBounds) {
        if (beReplacementAttempted || frameRays[0] >= RAY_BUDGET[0]) return false;
        beReplacementAttempted = true;
        Map.Entry<Object, Request> lowest = null;
        double lowestScore = Double.POSITIVE_INFINITY;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(currentWorld);
        for (Map.Entry<Object, Request> entry : queue.entrySet()) {
            Request request = entry.getValue();
            boolean inView = !view.outsideFrustum(request.bounds);
            double score = request.renderCost + Math.max(0L, frameId - request.enqueuedFrame) * 0.25D
                    + (inView ? 16.0D : 0.0D);
            if (score < lowestScore) { lowestScore = score; lowest = entry; }
        }
        double x = newBounds.getXsize(), y = newBounds.getYsize(), z = newBounds.getZsize();
        double incomingScore = Math.min(64.0D, Math.max(0.0D, 2.0D * (x * y + x * z + y * z)))
                + (view.outsideFrustum(newBounds) ? 0.0D : 16.0D);
        if (lowest != null && incomingScore > lowestScore) {
            Request evicted = queue.remove(lowest.getKey());
            clearCellHint(lowest.getKey(), evicted);
            cancelRequest(evicted);
            return true;
        }
        return false;
    }

    public static synchronized void onBlockChanged(Level currentWorld, BlockPos pos) {
        if (world != currentWorld || pos == null) return;
        CELL_HINTS.clear();
        blockEpoch++;
        for (Iterator<Map.Entry<Object, Result>> it = RESULTS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Object, Result> entry = it.next();
            Result result = entry == null ? null : entry.getValue();
            if (result == null || result.camera == null || result.bounds == null) {
                it.remove();
                continue;
            }
            if (crossesBlock(result.camera, result.bounds, pos)
                    || (result.occluded && frameCamera != null
                    && crossesBlock(frameCamera, result.bounds, pos))) {
                it.remove();
            }
        }
        for (Map<Object, Request> queue : REQUESTS) {
            queue.entrySet().removeIf(entry -> {
                Request r = entry == null ? null : entry.getValue();
                if (entry == null || entry.getKey() == null || r == null
                        || r.camera == null || r.bounds == null) {
                    if (r != null) cancelRequest(r);
                    return true;
                }
                if (crossesBlock(r.camera, r.bounds, pos)) {
                    cancelRequest(r);
                    return true;
                }
                return false;
            });
        }
        BE_RECHECKS.entrySet().removeIf(entry -> {
            Request request = entry == null ? null : entry.getValue();
            if (entry == null || entry.getKey() == null || request == null
                    || request.camera == null || request.bounds == null) {
                if (request != null) cancelRequest(request);
                return true;
            }
            if (crossesBlock(request.camera, request.bounds, pos)) {
                cancelRequest(request);
                return true;
            }
            return false;
        });
        for (int kind = 0; kind < QUERY_KIND_COUNT; kind++) {
            Map<Object, Request> requestQueue = REQUESTS[kind];
            PRIORITY_QUEUES[kind].removeIf(entry -> entry == null || entry.key == null
                    || entry.request == null || requestQueue.get(entry.key) != entry.request);
        }

    }
    private static void cancelRequests(Map<Object, Request> queue) {
        for (Request request : queue.values()) {
            cancelRequest(request);
        }
    }

    private static void evictResult(Object key, Result result) {
        if (key == null || result == null) return;
        for (Map<Object, Request> queue : REQUESTS) {
            Request request = queue.get(key);
            if (ownsResult(result, request)) {
                queue.remove(key);
                cancelRequest(request);
            }
        }
        Request recheck = BE_RECHECKS.get(key);
        if (ownsResult(result, recheck)) {
            BE_RECHECKS.remove(key);
            cancelRequest(recheck);
        }
        for (PriorityQueue<PriorityEntry> queue : PRIORITY_QUEUES) {
            queue.removeIf(entry -> key.equals(entry.key) && ownsResult(result, entry.request));
        }
        clearCellHint(key, result.owner);
    }
    private static boolean ownsResult(Result result, Request request) {
        return result.owner != null
                && request != null
                && result.owner == request
                && result.ownerGeneration == request.generation;
    }
    private static void cancelRequest(Request request) {
        if (request == null) return;
        if (request.inFlight != null) {
            request.inFlight.cancel(false);
        }
        request.inFlight = null;
        request.inFlightSample = -1;
        request.inFlightGeneration = -1L;
        request.generation++;
    }

    private static final class ResultCache extends LinkedHashMap<Object, Result> {
        private final int limit;
        private ResultCache(int limit) {
            super(512, 0.75F, true);
            this.limit = limit;
        }
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Result> eldest) {
            if (size() <= limit) return false;
            RenderDebugStats.recordLruEviction();
            evictResult(eldest.getKey(), eldest.getValue());
            return true;
        }
    }
    private static boolean crossesBlock(Vec3 camera, AABB bounds, BlockPos pos) {
        return new AABB(Math.min(camera.x, bounds.minX), Math.min(camera.y, bounds.minY),
                Math.min(camera.z, bounds.minZ), Math.max(camera.x, bounds.maxX),
                Math.max(camera.y, bounds.maxY), Math.max(camera.z, bounds.maxZ))
                .intersects(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
    }

    private static Vec3 boxSample(AABB box, int index) {
        double[] fractions = {0.1D, 0.5D, 0.9D};
        int x = index % 3;
        int y = (index / 3) % 3;
        int z = index / 9;
        return new Vec3(box.minX + box.getXsize() * fractions[x],
                box.minY + box.getYsize() * fractions[y],
                box.minZ + box.getZsize() * fractions[z]);
    }

    private static Vec3 trainSample(AABB box, int index) {
        double midX = (box.minX + box.maxX) * 0.5D;
        double midY = (box.minY + box.maxY) * 0.5D;
        double midZ = (box.minZ + box.maxZ) * 0.5D;
        return switch (index) {
            case 0 -> new Vec3(box.minX, midY, midZ);
            case 1 -> new Vec3(box.maxX, midY, midZ);
            case 2 -> new Vec3(midX, box.minY, midZ);
            case 3 -> new Vec3(midX, box.maxY, midZ);
            case 4 -> new Vec3(midX, midY, box.minZ);
            case 5 -> new Vec3(midX, midY, box.maxZ);
            default -> {
                if (index < 6 || index >= TRAIN_SAMPLE_COUNT) throw new IndexOutOfBoundsException(index);
                int corner = index - 6;
                yield new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
                        (corner & 2) == 0 ? box.minY : box.maxY,
                        (corner & 4) == 0 ? box.minZ : box.maxZ);
            }
        };
    }

    private static int faceMask(Vec3 camera, AABB box) {
        // Railbox occlusion is a full visibility proof. Keep every conservative
        // sample active; a partial face mask can turn a sparse witness into a
        // false all-occluded result after a small camera move.
        return (1 << SAMPLE_COUNT) - 1;
    }

    private static boolean validTrainBounds(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }

    private static final class CellHint {
        private final Level world;
        private final FrustumCache.CameraSnapshot view;
        private final long frame;
        private final long revision;
        private final long blockEpoch;
        private final Object sourceKey;
        private final Request request;
        private CellHint(Level world,
                         FrustumCache.CameraSnapshot view,
                         long frame,
                         long revision,
                         long blockEpoch,
                         Object sourceKey,
                         Request request) {
            this.world = world;
            this.view = view;
            this.frame = frame;
            this.revision = revision;
            this.blockEpoch = blockEpoch;
            this.sourceKey = sourceKey;
            this.request = request;
        }
        private boolean matches(Level world,
                                FrustumCache.CameraSnapshot view,
                                long frame,
                                long revision,
                                long blockEpoch) {
            return this.world == world && this.view == view && this.frame == frame
                    && this.revision == revision && this.blockEpoch == blockEpoch;
        }
    }
    private static final class Request {
        private final Level world;
        private Vec3 camera;
        private final AABB bounds;
        private final BlockPos ignoredPos;
        private final WeakReference<BlockEntity> target;
        private final boolean trainRay;
        private final boolean eyeCandyRay;
        private final RenderDebugStats.Category statsCategory;
        private final int sampleCount;
        private final Vec3[] samples;
        private final BlockPos[] blockers;
        private final BlockState[] blockerStates;
        private int nextSample;
        private long retryAfterFrame;
        private BlockPos unloadedPos;
        private int unknownRetries;
        private Future<OcclusionRaycaster.TraceResult> inFlight;
        private int inFlightSample = -1;
        private long generation;
        private long inFlightGeneration = -1L;
        private final long enqueuedFrame = frameId;
        private final double renderCost;
        private long priorityFrame = -1;
        private boolean inView;
        private boolean priorityQueued;
        private int sampleMask;

        private Request(Level world,
                        Vec3 camera,
                        AABB bounds,
                        BlockPos ignoredPos,
                        BlockEntity target,
                        boolean trainRay,
                        boolean eyeCandyRay) {
            this.world = world;
            this.camera = camera;
            this.bounds = bounds;
            this.ignoredPos = ignoredPos;
            this.target = target == null ? null : new WeakReference<>(target);
            this.trainRay = trainRay;
            this.eyeCandyRay = eyeCandyRay;
            this.statsCategory = RenderDebugStats.resolveCategory(
                    eyeCandyRay ? RenderDebugStats.Category.EYE_CANDY
                            : trainRay ? RenderDebugStats.Category.TRAIN
                            : target != null ? RenderDebugStats.Category.BE
                            : RenderDebugStats.Category.RAILBOX);
            this.sampleCount = trainRay ? TRAIN_SAMPLE_COUNT : SAMPLE_COUNT;
            this.samples = new Vec3[sampleCount];
            this.sampleMask = faceMask(camera, bounds);
            this.blockers = new BlockPos[sampleCount];
            this.blockerStates = new BlockState[sampleCount];
            double x = bounds.getXsize(), y = bounds.getYsize(), z = bounds.getZsize();
            this.renderCost = Math.min(64.0D, Math.max(0.0D, 2.0D * (x * y + x * z + y * z)));
        }

        private Vec3 sample(int index) {
            Vec3 value = samples[index];
            if (value == null) {
                value = trainRay ? trainSample(bounds, index) : boxSample(bounds, index);
                samples[index] = value;
            }
            return value;
        }

        @SuppressWarnings("deprecation")
        private void retarget(Vec3 camera) {
            if (!this.camera.equals(camera)) {
                if (inFlight != null) {
                    int cancelledSample = inFlightSample;
                    cancelRequest(this);
                    if (cancelledSample >= 0) nextSample = Math.min(nextSample, cancelledSample);
                }
                this.unloadedPos = null;
                int previousMask = this.sampleMask;
                this.sampleMask = faceMask(camera, bounds);
                boolean reusable = world != null && blockers != null && blockerStates != null
                        && blockers.length == blockerStates.length && previousMask == this.sampleMask;
                for (int i = 0; i < nextSample; i++) {
                    if ((sampleMask & (1 << i)) == 0) continue;
                    if (!reusable || i >= blockers.length) {
                        reusable = false;
                        break;
                    }
                    if (blockers[i] == null || blockerStates[i] == null
                            || !world.hasChunkAt(blockers[i])
                            || world.getBlockState(blockers[i]) != blockerStates[i]
                            || !OcclusionRaycaster.witnessBlocksRay(world, camera,
                            sample(i), blockers[i], ignoredPos)) {
                        reusable = false;
                        break;
                    }
                }
                this.camera = camera;
                if (!reusable) this.nextSample = 0;
                if (!reusable) {
                    this.retryAfterFrame = 0;
                    this.unknownRetries = 0;
                }
                if (!reusable) {
                    if (blockers != null) java.util.Arrays.fill(blockers, null);
                    if (blockerStates != null) java.util.Arrays.fill(blockerStates, null);
                }
            }
        }

        private boolean matches(Level world, Vec3 camera, AABB bounds, BlockEntity target) {
            return this.world == world
                    && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }
    }

    private static final class Result {

        private final Level world;

        private final Vec3 camera;

        private final float yaw;

        private final float pitch;

        private final AABB bounds;

        private final WeakReference<BlockEntity> target;

        private final Request owner;
        private final long ownerGeneration;
        private final boolean occluded;

        private final long completedAt;

        private final long completedFrame;

        private final BlockPos[] blockers;

        private final BlockState[] blockerStates;

        private int nextBlocker;

        private long lastValidationFrame = -1;

        private long lastWitnessFrame = -1;

        private long movementGraceStarted;

        private boolean blockersValid = true;

        private Result(Level world,
                       Vec3 camera,
                       FrustumCache.CameraSnapshot view,
                       AABB bounds,
                       WeakReference<BlockEntity> target,
                       Request owner,
                       long ownerGeneration,
                       boolean occluded,
                       long completedAt,
                       BlockPos[] blockers,
                       BlockState[] blockerStates) {
            this.world = world;
            this.camera = camera;
            this.yaw = view == null ? Float.NaN : view.yaw();
            this.pitch = view == null ? Float.NaN : view.pitch();
            this.bounds = bounds;
            this.target = target;
            this.owner = owner;
            this.ownerGeneration = ownerGeneration;
            this.occluded = occluded;
            this.completedAt = completedAt;
            this.completedFrame = frameId;
            this.blockers = blockers;
            this.blockerStates = blockerStates;
        }

        private boolean validateNextBlocker(Level world, long frame) {
            if (!occluded || lastValidationFrame == frame) return blockersValid;
            lastValidationFrame = frame;
            BlockPos pos = blockers[nextBlocker];
            if (pos != null && world.getBlockState(pos) != blockerStates[nextBlocker]) {
                blockersValid = false;
            }
            nextBlocker = (nextBlocker + 1) % blockers.length;
            return blockersValid;
        }

        private boolean cameraMoved(Vec3 currentCamera) {
            return camera == null || currentCamera == null
                    || camera.distanceToSqr(currentCamera) > 1.0E-12D;
        }

        private boolean revalidateBoxWitness(Level world, Vec3 currentCamera, long frame) {
            if (!occluded || !cameraMoved(currentCamera)) return blockersValid;
            if (lastWitnessFrame == frame) return blockersValid;
            lastWitnessFrame = frame;
            if (world == null || currentCamera == null || owner == null
                    || owner.sampleCount < SAMPLE_COUNT || blockers == null
                    || blockerStates == null || blockers.length < SAMPLE_COUNT
                    || blockerStates.length < SAMPLE_COUNT) {
                blockersValid = false;
                return false;
            }
            for (int index = 0; index < SAMPLE_COUNT; index++) {
                BlockPos blocker = blockers[index];
                BlockState state = blockerStates[index];
                if (blocker == null || state == null || !world.hasChunkAt(blocker)
                        || world.getBlockState(blocker) != state
                        || !OcclusionRaycaster.witnessBlocksRay(world, currentCamera,
                        owner.sample(index), blocker, owner.ignoredPos)) {
                    blockersValid = false;
                    return false;
                }
            }
            return true;
        }

        private boolean matches(Level world,
                                FrustumCache.CameraSnapshot view,
                                AABB bounds,
                                BlockEntity target) {
            Vec3 camera = view == null ? null : view.cameraPosition();
            return this.world == world && this.camera != null && camera != null
                    && this.camera.distanceToSqr(camera) <= SMALL_CAMERA_MOVE_SQR
                    && rotationMatches(view)
                    && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }

        private boolean matchesTarget(Level world,
                                      FrustumCache.CameraSnapshot view,
                                      AABB bounds,
                                      BlockEntity target) {
            return this.world == world && rotationMatches(view) && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }

        private boolean rotationMatches(FrustumCache.CameraSnapshot view) {
            return view != null
                    && Math.abs(yaw - view.yaw()) <= SMALL_CAMERA_ROTATION
                    && Math.abs(pitch - view.pitch()) <= SMALL_CAMERA_ROTATION;
        }

        private boolean fresh(long now) {
            long age = frameId - completedFrame;
            return age >= 0L && age < MAX_RESULT_REUSE_FRAMES
                    && now - completedAt < (occluded ? OCCLUDED_TTL_NS : VISIBLE_TTL_NS);
        }

        private boolean movementGrace(long now) {
            if (!fresh(now)) return false;
            if (movementGraceStarted == 0L) movementGraceStarted = now;
            return now - movementGraceStarted < OCCLUDED_RECHECK_GRACE_NS;
        }

        private boolean overflowGrace(long now, Object key) {
            long staggeredLimit = 100_000_000L + Math.floorMod(key.hashCode(), 151) * 1_000_000L;
            return movementGraceStarted != 0L && now - movementGraceStarted < staggeredLimit;
        }
    }

    private record PriorityEntry(Object key, Request request, double priority, long sequence) {}

    private record CellHintKey(int kind, long x, long y, long z) {}
    private record BoxKey(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {}

    private record EyeCandyKey(long packedPos) {}
}
