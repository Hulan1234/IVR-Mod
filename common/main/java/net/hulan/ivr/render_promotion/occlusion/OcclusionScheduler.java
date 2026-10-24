package net.hulan.ivr.render_promotion.occlusion;

import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** 为方块实体、列车和线路片段分配逐帧遮挡射线预算。
 * 缓存结果必须匹配世界、相机和包围盒；未知状态一律放行。 */
public final class OcclusionScheduler {
    private static final int QUERY_KIND_COUNT = 4;
    /** Dynamic per-frame ray budget: BE, train, generic (rail/catenary), EyeCandy. */
    private static final int MAX_TOTAL_RAYS = 128;
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
    private static int budgetReason;
    private static int backlogStreak;
    private static int idleStreak;
    private static int allocationCursor;
    private static long frameDrainNanos;
    private static int frameFutureCompleted;
    private static int frameFutureErrors;
    private static int frameQueueRejected;
    private static int frameBackoff;
    /** 每类待处理查询的队列容量。
     * 容量已满时按 PENDING 放行。 */
    private static final int[] QUEUE_LIMIT = {2048, 256, 256, 512};
    /** 每个包围盒的采样点数。
     * 三个轴各取三个比例位置。 */
    private static final int SAMPLE_COUNT = 27;
    private static final int TRAIN_SAMPLE_COUNT = 14;
    /** 允许复查方块实体时保留结果的相机小幅位移。
     * 列车仍须用当前视角重新证明完整包围盒。 */
    private static final double SMALL_CAMERA_MOVE_SQR = 0.75D * 0.75D;
    // Witness checks normally catch block replacement within 15 frames. The
    // TTL also bounds changes that bypass setBlock and keep the same state.
    /** 遮挡结果的最长缓存时间。
     * 期间仍逐帧抽查见证方块。 */
    private static final long OCCLUDED_TTL_NS = 12_000_000_000L;
    /** 可见结果的最长缓存时间。
     * 过期后重新加入射线队列。 */
    private static final long VISIBLE_TTL_NS = 8_000_000_000L;
    /** 方块实体重检期间保留遮挡的短暂窗口。
     * 不用于列车的完整包围盒证明。 */
    private static final long OCCLUDED_RECHECK_GRACE_NS = 250_000_000L;
    /** 通用包围盒每帧新请求数量上限。
     * 超额请求保持 PENDING。 */
    private static final int MAX_BOX_REQUESTS_PER_FRAME = 24;
    private static final int MAX_EYECANDY_REQUESTS_PER_FRAME = 32;
    /** 按目标类型分开的有序待处理队列。
     * 仅在渲染线程推进。 */
    private static final Map<Object, Request>[] REQUESTS = queues();
    /** 方块实体重检队列容量。
     * 避免背景重检无限积累。 */
    private static final int BE_RECHECK_LIMIT = 256;
    /** 方块实体重检预留射线数。
     * 避免被普通新请求完全占满。 */
    private static final int BE_RECHECK_RESERVED_RAYS = 8;
    private static final int BE_RECHECK_BACKLOG_RAYS = 4;
    /** 正在重检但可短暂保留结果的方块实体。
     * 相机或见证方块失效时移除。 */
    private static final Map<Object, Request> BE_RECHECKS = new LinkedHashMap<>();
    /** 最近完成的遮挡结果。
     * 查询时重新核对世界、相机、边界与有效期。 */
    private static final Map<Object, Result> RESULTS = new LinkedHashMap<>();
    /** 当前缓存对应的世界。
     * 世界切换时清空请求和结果。 */
    private static Level world;
    /** 当前帧所用相机位置。
     * 大幅移动会使旧射线失效。 */
    private static Vec3 frameCamera;
    /** 最近同步的渲染帧编号。
     * 用于刷新预算与见证抽查。 */
    private static long frameId = -1;
    /** 本帧已接纳的通用盒体请求数。
     * 每个新帧重置。 */
    private static int boxRequests;
    private static int eyeCandyRequests;
    private static boolean beReplacementAttempted;
    /** 各查询类型本帧已发出的射线数。
     * drain 不得超过对应配额。 */
    private static final int[] frameRays = new int[QUERY_KIND_COUNT];
    /** Successfully accepted ray evaluations; rejected submissions are excluded. */
    private static final int[] frameSubmitted = new int[QUERY_KIND_COUNT];

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
        if (REQUESTS.length != QUERY_KIND_COUNT || QUEUE_LIMIT.length != QUERY_KIND_COUNT
                || INITIAL_RAY_BUDGET.length != QUERY_KIND_COUNT
                || MIN_RAY_BUDGET.length != QUERY_KIND_COUNT
                || RAY_BUDGET.length != QUERY_KIND_COUNT || frameRays.length != QUERY_KIND_COUNT
                || frameSubmitted.length != QUERY_KIND_COUNT) {
            throw new IllegalStateException("Occlusion query kind arrays are inconsistent");
        }
        if (initialTotal != INITIAL_TOTAL_RAYS || minimumTotal != MIN_TOTAL_RAYS
                || initialTotal > MAX_TOTAL_RAYS) {
            throw new IllegalStateException("Occlusion total and category budgets are inconsistent");
        }
    }

    /** 调度器只提供静态渲染线程入口。
     * 不创建实例或额外工作线程。 */
    private OcclusionScheduler() {}

    /** Stop queued ray work during client teardown and discard world-bound state. */
    public static synchronized void shutdown() {
        for (Map<Object, Request> queue : REQUESTS) {
            cancelRequests(queue);
            queue.clear();
        }
        cancelRequests(BE_RECHECKS);
        BE_RECHECKS.clear();
        RESULTS.clear();
        world = null;
        frameCamera = null;
        frameId = -1L;
        budgetTotal = INITIAL_TOTAL_RAYS;
        budgetReason = 0;
        backlogStreak = idleStreak = allocationCursor = 0;
        frameDrainNanos = 0L;
        frameFutureCompleted = frameFutureErrors = frameQueueRejected = frameBackoff = 0;
        System.arraycopy(INITIAL_RAY_BUDGET, 0, RAY_BUDGET, 0, QUERY_KIND_COUNT);
        OcclusionRaycaster.shutdown();
    }

    /** 为每种查询类别创建独立的按插入顺序队列。
     * 调度时按类别分别执行预算。 */
    @SuppressWarnings("unchecked")
    private static Map<Object, Request>[] queues() {
        Map<Object, Request>[] queues = new Map[QUERY_KIND_COUNT];
        for (int kind = 0; kind < queues.length; kind++) queues[kind] = new LinkedHashMap<>();
        return queues;
    }

    /** 对调用方公开的可见性状态。
     * PENDING 表示尚无可用于剔除的结论。 */
    public enum ResultType { VISIBLE, OCCLUDED, PENDING, UNLOADED }

    /** 返回查询状态及列车首个面心采样的候选遮挡方块。
     * 见证方块仅供调用方再次检查，不能单独作为隐藏依据。 */
    public record QueryResult(ResultType type, BlockPos witness) {
        /** 只在完成遮挡查询时允许调用方考虑隐藏。
         * 可见与未完成状态都应继续绘制。 */
        public boolean mayRender() { return type != ResultType.OCCLUDED && type != ResultType.UNLOADED; }
    }

    /** 不携带见证方块的可见结果。
     * 所有调用方均应继续绘制。 */
    private static final QueryResult VISIBLE = new QueryResult(ResultType.VISIBLE, null);
    /** 非列车专用的遮挡结果常量。
     * 列车查询会返回带候选见证的新结果。 */
    private static final QueryResult OCCLUDED = new QueryResult(ResultType.OCCLUDED, null);
    /** 未完成查询的放行结果。
     * 不应据此隐藏任何目标。 */
    private static final QueryResult PENDING = new QueryResult(ResultType.PENDING, null);
    private static final QueryResult UNLOADED = new QueryResult(ResultType.UNLOADED, null);

    /** 将盒体坐标量化为可复用的查询键。
     * 相同几何区域可以共享缓存结果。 */
    public static Object boxKey(AABB bounds) {
        return new BoxKey(Math.round(bounds.minX * 16.0D), Math.round(bounds.minY * 16.0D),
                Math.round(bounds.minZ * 16.0D), Math.round(bounds.maxX * 16.0D),
                Math.round(bounds.maxY * 16.0D), Math.round(bounds.maxZ * 16.0D));
    }

    /** Stable key namespace for EyeCandy block entities. */
    public static Object eyeCandyKey(BlockPos pos) {
        return new EyeCandyKey(pos == null ? 0L : pos.asLong());
    }

    /** 使用默认目标类型发起遮挡查询。
     * 未完成的请求交由后续帧继续推进。 */
    public static QueryResult query(Level currentWorld, Object key, AABB bounds, BlockPos ignoredPos) {
        return query(currentWorld, key, bounds, ignoredPos, null);
    }

    /** EyeCandy uses its own queue, budget and cache key namespace. */
    public static QueryResult queryEyeCandy(Level currentWorld, Object key, AABB bounds,
                                             BlockPos ignoredPos) {
        return queryInternal(currentWorld, key, bounds, ignoredPos, null, EYE_CANDY_KIND);
    }

    /** 只读取通用盒体的既有缓存结论。
     * 无匹配或已过期的结果返回 PENDING。 */
    public static QueryResult cachedBox(Level currentWorld, AABB bounds) {
        if (currentWorld == null || bounds == null) return PENDING;
        Vec3 camera = FrustumCache.capture(currentWorld).cameraPosition();
        begin(currentWorld, camera);
        Result result = RESULTS.get(boxKey(bounds));
        if (result == null || !result.matches(currentWorld, camera, bounds, null)
                || !result.validateNextBlocker(currentWorld, frameId)
                || !result.fresh(System.nanoTime())) {
            return PENDING;
        }
        return result.occluded ? OCCLUDED : VISIBLE;
    }

    /** 校验缓存后按类别接纳并推进射线请求。
     * 列车遮挡结果附带首个面心采样方块供完整投影复核。 */
    public static QueryResult query(Level currentWorld, Object key, AABB bounds,
                                    BlockPos ignoredPos, BlockEntity target) {
        return queryInternal(currentWorld, key, bounds, ignoredPos, target, -1);
    }

    @SuppressWarnings("deprecation")
    private static QueryResult queryInternal(Level currentWorld, Object key, AABB bounds,
                                             BlockPos ignoredPos, BlockEntity target, int forcedKind) {
        if (currentWorld == null || key == null || bounds == null) return VISIBLE;
        Vec3 camera = FrustumCache.capture(currentWorld).cameraPosition();
        begin(currentWorld, camera);
        int kind = forcedKind >= 0 ? forcedKind : target != null ? 0 : key instanceof BoxKey ? 2 : 1;
        if (kind == 1 && !validTrainBounds(bounds)) {
            REQUESTS[1].remove(key);
            RESULTS.remove(key);
            return PENDING;
        }
        long now = System.nanoTime();
        Result result = RESULTS.get(key);
        if (result != null && result.matches(currentWorld, camera, bounds, target)
                && result.validateNextBlocker(currentWorld, frameId)
                && result.fresh(now)) {
            return result.occluded ? new QueryResult(ResultType.OCCLUDED,
                    kind != 2 ? result.blockers[0] : null) : VISIBLE;
        }
        if (result != null && !result.blockersValid) {
            RESULTS.remove(key);
            result = null;
        }
        boolean recheckingOccluded = kind == 0 && result != null
                && result.matchesTarget(currentWorld, bounds, target)
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
        if (request == null || !request.matches(currentWorld, camera, bounds, target)) {
            if (request != null) {
                // Replacing a key must cancel the old snapshot task.  The
                // worker has no callback, but cancellation avoids burning CPU
                // on a camera/target generation that can no longer be used.
                cancelRequest(request);
            }
            if (request == null && queue.size() >= (recheckingOccluded ? BE_RECHECK_LIMIT : QUEUE_LIMIT[kind])) {
                if (!recheckingOccluded && kind == 0 && evictLowestPriority(queue, currentWorld, bounds)) {
                    // Make room for a newly visible, higher-priority entity.
                } else {
                    frameQueueRejected++;
                    RenderDebugStats.recordQueueRejected(statsCategory(kind));
                    if (recheckingOccluded && result.overflowGrace(now, key)) return OCCLUDED;
                    RESULTS.remove(key);
                    return PENDING;
                }
            }
            if (kind == 2 && boxRequests >= MAX_BOX_REQUESTS_PER_FRAME) {
                frameQueueRejected++;
                RenderDebugStats.recordQueueRejected(statsCategory(kind));
                return PENDING;
            }
            if (kind == 2) boxRequests++;
            if (kind == EYE_CANDY_KIND && eyeCandyRequests >= MAX_EYECANDY_REQUESTS_PER_FRAME) {
                frameQueueRejected++;
                RenderDebugStats.recordQueueRejected(statsCategory(kind));
                return PENDING;
            }
            if (kind == EYE_CANDY_KIND) eyeCandyRequests++;
            request = new Request(currentWorld, camera, bounds, ignoredPos, target,
                    kind == 1, kind == EYE_CANDY_KIND);
            queue.put(key, request);
        }
        drain(kind, currentWorld);
        if (request.unloadedPos != null) {
            if (!currentWorld.hasChunkAt(request.unloadedPos)) return PENDING;
            request.unloadedPos = null;
            request.retryAfterFrame = frameId;
        }
        result = RESULTS.get(key);
        if (result != null && result.matches(currentWorld, camera, bounds, target)
                && result.validateNextBlocker(currentWorld, frameId)
                && result.fresh(System.nanoTime())) {
            return result.occluded ? new QueryResult(ResultType.OCCLUDED,
                    kind != 2 ? result.blockers[0] : null) : VISIBLE;
        }
        if (result != null && !result.blockersValid) {
            RESULTS.remove(key);
            BE_RECHECKS.remove(key);
            return PENDING;
        }
        return recheckingOccluded && BE_RECHECKS.containsKey(key) ? OCCLUDED : PENDING;
    }

    /** 世界或相机变化时清理无效工作，并刷新逐帧预算。
     * 缓存大小也在此处限制。 */
    private static void begin(Level currentWorld, Vec3 camera) {
        if (world != currentWorld && Minecraft.getInstance().level == currentWorld) {
            OcclusionRaycaster.start();
        } else {
            OcclusionRaycaster.ensureRunning();
        }
        if (world != currentWorld
                || (frameCamera != null && frameCamera.distanceToSqr(camera) > SMALL_CAMERA_MOVE_SQR)) {
            for (Map<Object, Request> queue : REQUESTS) {
                cancelRequests(queue);
                queue.clear();
            }
            cancelRequests(BE_RECHECKS);
            BE_RECHECKS.clear();
            RESULTS.clear();
            world = currentWorld;
        }
        frameCamera = camera;
        long currentFrame = FrustumCache.frameId();
        if (frameId != currentFrame) {
            if (frameId >= 0L) adjustBudget();
            frameId = currentFrame;
            boxRequests = 0;
            eyeCandyRequests = 0;
            beReplacementAttempted = false;
            Arrays.fill(frameRays, 0);
            Arrays.fill(frameSubmitted, 0);
            frameDrainNanos = 0L;
            frameFutureCompleted = frameFutureErrors = frameQueueRejected = frameBackoff = 0;
            while (RESULTS.size() > 4096) {
                RESULTS.remove(RESULTS.keySet().iterator().next());
            }
        }
    }

    /** 在该目标类别的射线预算内推进请求。
     * 方块实体重检先获得保留配额。 */
    private static void drain(int kind, Level currentWorld) {
        if (frameRays[kind] >= RAY_BUDGET[kind]) return;
        long started = System.nanoTime();
        if (kind == 0) {
            int recheckLimit = REQUESTS[0].isEmpty()
                    ? BE_RECHECK_RESERVED_RAYS : BE_RECHECK_BACKLOG_RAYS;
            drainQueue(BE_RECHECKS, kind, currentWorld, recheckLimit);
            drainQueue(REQUESTS[kind], kind, currentWorld, RAY_BUDGET[kind]);
            drainQueue(BE_RECHECKS, kind, currentWorld, RAY_BUDGET[kind]);
        } else {
            drainQueue(REQUESTS[kind], kind, currentWorld, RAY_BUDGET[kind]);
        }
        frameDrainNanos += Math.max(0L, System.nanoTime() - started);
    }

    /** 逐请求走查采样点并保存可见或遮挡结果。
     * UNKNOWN 延后重试，避免把未加载区块当作墙体。 */
    private static void drainQueue(Map<Object, Request> queue, int kind, Level currentWorld, int limit) {
        while (!queue.isEmpty() && frameRays[kind] < limit) {
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
                    queue.remove(entry.getKey());
                    // Expiring the hiding grace must not discard valid sampling progress.
                    BlockEntity recheckTarget = request.target == null ? null : request.target.get();
                    if (previous != null && previous.blockersValid
                            && request.matches(currentWorld, frameCamera, request.bounds, recheckTarget)
                            && REQUESTS[0].size() < QUEUE_LIMIT[0]) {
                        REQUESTS[0].putIfAbsent(entry.getKey(), request);
                    }
                    continue;
                }
            }
            BlockEntity target = request.target == null ? null : request.target.get();
            if ((request.target != null && target == null)
                    || !request.matches(currentWorld, frameCamera, request.bounds, target)) {
                queue.remove(entry.getKey());
                continue;
            }
            if (request.retryAfterFrame > frameId) continue;
            boolean visible = false;

            // A worker only receives immutable ray snapshots.  Harvest a completed
            // result on the render thread before submitting the next sample.
            if (request.inFlight != null) {
                if (!request.inFlight.isDone()) continue;
                OcclusionRaycaster.TraceResult sight;
                boolean futureError = false;
                try {
                    sight = request.inFlight.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (ExecutionException failure) {
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (CancellationException cancelled) {
                    // World/camera invalidation cancels in-flight work.  A
                    // cancelled Future is a retryable unknown, never a render crash.
                    futureError = true;
                    sight = new OcclusionRaycaster.TraceResult(OcclusionRaycaster.Sight.UNKNOWN, null);
                } catch (RuntimeException failure) {
                    // A provider or a malformed completion must not escape the
                    // render loop; retry conservatively on a later frame.
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
                int sampleIndex = request.inFlightSample;
                long completedGeneration = request.inFlightGeneration;
                request.inFlight = null;
                request.inFlightGeneration = -1L;
                if (completedGeneration != request.generation) {
                    request.nextSample = Math.max(0, Math.min(request.nextSample, sampleIndex));
                    request.retryAfterFrame = frameId + 1;
                    continue;
                }
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
                queue.remove(entry.getKey());
                RESULTS.put(entry.getKey(), new Result(currentWorld, frameCamera,
                        request.bounds, request.target, !visible, System.nanoTime(),
                        request.blockers, request.blockerStates));
                continue;
            }

            while (request.nextSample < request.sampleCount && frameRays[kind] < limit) {
                int sampleIndex = request.nextSample++;
                Vec3 sample = sample(request.bounds, sampleIndex, kind);
                frameRays[kind]++;
                int maxSteps = request.trainRay ? 1024 : 256;
                OcclusionRaycaster.RaySnapshot snapshot = OcclusionRaycaster.prepare(
                        currentWorld, frameCamera, sample, request.ignoredPos, maxSteps);
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
                    frameSubmitted[kind]++;
                    request.inFlight = future;
                    request.inFlightSample = sampleIndex;
                    request.inFlightGeneration = ++request.generation;
                    break;
                }
                frameSubmitted[kind]++;
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
                queue.remove(entry.getKey());
                RESULTS.put(entry.getKey(), new Result(currentWorld, frameCamera,
                        request.bounds, request.target, !visible, System.nanoTime(),
                        request.blockers, request.blockerStates));
            }
        }
    }

    private static Map.Entry<Object, Request> nextRequest(Map<Object, Request> queue,
                                                          int kind, Level currentWorld) {
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
            // Surface area is a cheap geometry-cost estimate; aging prevents starvation.
            double score = request.renderCost + Math.max(0L, frameId - request.enqueuedFrame) * 0.25D;
            score += request.inView ? 16.0D : 0.0D;
            if (best == null || score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return best;
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

    /** Adjusts only the number of non-blocking submissions made next frame. */
    private static void adjustBudget() {
        int queued = BE_RECHECKS.size();
        for (Map<Object, Request> requestQueue : REQUESTS) queued += requestQueue.size();
        boolean saturationPressure = frameQueueRejected > 0 || frameBackoff >= 4;
        boolean futurePressure = frameFutureErrors > frameFutureCompleted
                || (queued > 0 && frameFutureCompleted == 0 && frameDrainNanos > 1_000_000L);
        boolean slow = frameDrainNanos > 3_000_000L;
        boolean backlogPressure = queued > budgetTotal;
        boolean severeBacklog = queued > budgetTotal * 2;
        if (slow || futurePressure || saturationPressure) {
            budgetTotal = Math.max(MIN_TOTAL_RAYS, budgetTotal - BUDGET_SHRINK_STEP);
            budgetReason = slow ? 2 : futurePressure ? 4 : 5;
            backlogStreak = 0;
            idleStreak = 0;
        } else if (severeBacklog) {
            // A large queue is a fast-movement burst: grow immediately, but
            // only after all safety signals above have cleared.
            budgetTotal = Math.min(MAX_TOTAL_RAYS, budgetTotal + BUDGET_SURGE_STEP);
            budgetReason = 1;
            backlogStreak = 0;
            idleStreak = 0;
        } else if (backlogPressure) {
            backlogStreak++;
            idleStreak = 0;
            if (backlogStreak >= BUDGET_GROWTH_WINDOW) {
                budgetTotal = Math.min(MAX_TOTAL_RAYS, budgetTotal + BUDGET_GROW_STEP);
                backlogStreak = 0;
                budgetReason = 1;
            } else {
                budgetReason = 0;
            }
        } else if (queued == 0 && frameFutureErrors == 0 && frameDrainNanos < 1_000_000L) {
            idleStreak++;
            backlogStreak = 0;
            if (idleStreak >= BUDGET_IDLE_WINDOW) {
                budgetTotal = Math.max(MIN_TOTAL_RAYS, budgetTotal - BUDGET_SHRINK_STEP);
                idleStreak = 0;
                budgetReason = 3;
            } else {
                budgetReason = 0;
            }
        } else {
            backlogStreak = 0;
            idleStreak = 0;
            budgetReason = 0;
        }
        allocateBudget();
    }

    /** Distributes the total while retaining a minimum for every query kind. */
    private static void allocateBudget() {
        budgetTotal = Math.max(MIN_TOTAL_RAYS, Math.min(MAX_TOTAL_RAYS, budgetTotal));
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

    /** Text is built only when the F3 overlay asks for it. */
    public static String debugBudgetLine() {
        String reason = budgetReason == 1 ? "backlog" : budgetReason == 2 ? "slow"
                : budgetReason == 3 ? "idle" : budgetReason == 4 ? "future"
                : budgetReason == 5 ? "saturated" : "steady";
        int used = 0;
        int submitted = 0;
        for (int kind = 0; kind < QUERY_KIND_COUNT; kind++) {
            used += frameRays[kind];
            submitted += frameSubmitted[kind];
        }
        return "IVR ray budget " + budgetTotal + "/" + MAX_TOTAL_RAYS
                + " used=" + used + " submitted=" + submitted + " remaining="
                + Math.max(0, budgetTotal - used) + " [BE=" + budgetLine(0)
                + " Train=" + budgetLine(1) + " Generic=" + budgetLine(2)
                + " EyeCandy=" + budgetLine(EYE_CANDY_KIND) + "] " + reason;
    }

    private static String budgetLine(int kind) {
        // Each category is budget/consumed/accepted/remaining for this frame.
        return RAY_BUDGET[kind] + "/" + frameRays[kind] + "/" + frameSubmitted[kind]
                + "/" + Math.max(0, RAY_BUDGET[kind] - frameRays[kind]);
    }

    private static boolean evictLowestPriority(Map<Object, Request> queue, Level currentWorld,
                                               AABB newBounds) {
        // Overflowing render calls must not repeatedly scan the full queue.
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
            queue.remove(lowest.getKey());
            return true;
        }
        return false;
    }

    /** 方块变化时移除可能穿过该方块的结果与请求。
     * 后续渲染查询将重新验证。 */
    public static void onBlockChanged(Level currentWorld, BlockPos pos) {
        if (world != currentWorld) return;
        for (Iterator<Map.Entry<Object, Result>> it = RESULTS.entrySet().iterator(); it.hasNext();) {
            Result result = it.next().getValue();
            if (crossesBlock(result.camera, result.bounds, pos)
                    || (result.occluded && frameCamera != null
                    && crossesBlock(frameCamera, result.bounds, pos))) {
                it.remove();
            }
        }
        for (Map<Object, Request> queue : REQUESTS) {
            queue.entrySet().removeIf(entry -> {
                Request r = entry.getValue();
                if (crossesBlock(r.camera, r.bounds, pos)) {
                    cancelRequest(r);
                    return true;
                }
                return false;
            });
        }
        BE_RECHECKS.entrySet().removeIf(entry -> {
            Request request = entry.getValue();
            if (crossesBlock(request.camera, request.bounds, pos)) {
                cancelRequest(request);
                return true;
            }
            return false;
        });
    }

    private static void cancelRequests(Map<Object, Request> queue) {
        for (Request request : queue.values()) {
            cancelRequest(request);
        }
    }

    /** Cancel a worker snapshot and invalidate every completion token. */
    private static void cancelRequest(Request request) {
        if (request == null) return;
        if (request.inFlight != null) request.inFlight.cancel(false);
        request.inFlight = null;
        request.inFlightSample = -1;
        request.inFlightGeneration = -1L;
        request.generation++;
    }

    /** 用相机到目标的保守外包范围检查方块影响。
     * 命中时优先清除缓存，允许重新渲染。 */
    private static boolean crossesBlock(Vec3 camera, AABB bounds, BlockPos pos) {
        return new AABB(Math.min(camera.x, bounds.minX), Math.min(camera.y, bounds.minY),
                Math.min(camera.z, bounds.minZ), Math.max(camera.x, bounds.maxX),
                Math.max(camera.y, bounds.maxY), Math.max(camera.z, bounds.maxZ))
                .intersects(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
    }

    /** 按三乘三乘三网格生成盒体采样点。
     * 这些点只是候选筛查，不证明列车完整遮挡。 */
    private static Vec3 sample(AABB box, int index, int kind) {
        if (kind == 0) {
            double insetX = Math.min(box.getXsize() * 0.02D, 0.01D);
            double insetY = Math.min(box.getYsize() * 0.02D, 0.01D);
            double insetZ = Math.min(box.getZsize() * 0.02D, 0.01D);
            double minX = box.minX + insetX, maxX = box.maxX - insetX;
            double minY = box.minY + insetY, maxY = box.maxY - insetY;
            double minZ = box.minZ + insetZ, maxZ = box.maxZ - insetZ;
            double midX = (minX + maxX) * 0.5D, midY = (minY + maxY) * 0.5D,
                    midZ = (minZ + maxZ) * 0.5D;
            if (index < 8) {
                return new Vec3((index & 1) == 0 ? minX : maxX,
                        (index & 2) == 0 ? minY : maxY,
                        (index & 4) == 0 ? minZ : maxZ);
            }
            if (index < 14) {
                return switch (index - 8) {
                    case 0 -> new Vec3(box.minX, midY, midZ);
                    case 1 -> new Vec3(box.maxX, midY, midZ);
                    case 2 -> new Vec3(midX, box.minY, midZ);
                    case 3 -> new Vec3(midX, box.maxY, midZ);
                    case 4 -> new Vec3(midX, midY, box.minZ);
                    default -> new Vec3(midX, midY, box.maxZ);
                };
            }
            if (index < 26) {
                int edge = index - 14;
                int axis = edge / 4, bits = edge % 4;
                double a = (bits & 1) == 0 ? minX : maxX;
                double b = (bits & 2) == 0 ? minY : maxY;
                double c = (bits & 2) == 0 ? minZ : maxZ;
                return axis == 0 ? new Vec3(midX, (bits & 1) == 0 ? minY : maxY, c)
                        : axis == 1 ? new Vec3(a, midY, c) : new Vec3(a, b, midZ);
            }
            return new Vec3(midX, midY, midZ);
        }
        if (kind == 1) {
            double midX = box.minX * 0.5D + box.maxX * 0.5D;
            double midY = box.minY * 0.5D + box.maxY * 0.5D;
            double midZ = box.minZ * 0.5D + box.maxZ * 0.5D;
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
        double[] fractions = {0.1D, 0.5D, 0.9D};
        int x = index % 3;
        int y = (index / 3) % 3;
        int z = index / 9;
        return new Vec3(box.minX + box.getXsize() * fractions[x],
                box.minY + box.getYsize() * fractions[y],
                box.minZ + box.getZsize() * fractions[z]);
    }

    private static boolean validTrainBounds(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }

    /** 一次尚未完成的目标采样工作。
     * 保存已查样本与见证方块，并在预算内逐帧推进。 */
    private static final class Request {
        /** 请求所属世界与相机位置。
         * 不匹配当前渲染环境时请求失效。 */
        private final Level world;
        /** 请求最近使用的相机位置。
         * 相机移动时须核对当前请求能否沿用。 */
        private Vec3 camera;
        /** 查询目标的包围盒和可忽略自身方块。
         * 边界变化时必须重新采样。 */
        private final AABB bounds;
        /** 射线应忽略的目标自身方块。
         * 未指定时检查全部经过方块。 */
        private final BlockPos ignoredPos;
        /** 方块实体目标的弱引用。
         * 目标卸载后不会被队列强行保活。 */
        private final WeakReference<BlockEntity> target;
        /** 列车查询使用较长射线步进预算。
         * 不改变每帧射线总数配额。 */
        private final boolean trainRay;
        private final boolean eyeCandyRay;
        private final RenderDebugStats.Category statsCategory;
        private final int sampleCount;
        /** 每个已遮挡采样点的方块与方块状态。
         * 完成后用于快速变化检查和列车候选见证。 */
        private final BlockPos[] blockers;
        /** 各候选方块完成采样时的状态。
         * 后续状态变化会使结果失效。 */
        private final BlockState[] blockerStates;
        /** 下一个采样点索引。
         * 只有完成全部采样才产生遮挡结果。 */
        private int nextSample;
        /** UNKNOWN 重试的下一帧及退避级别。
         * 避免未加载区块持续占用预算。 */
        private long retryAfterFrame;
        private BlockPos unloadedPos;
        /** UNKNOWN 连续发生时的退避级别。
         * 成功采样后重置为零。 */
        private int unknownRetries;
        /** 后台快照判定任务；只在渲染线程收割。 */
        private Future<OcclusionRaycaster.TraceResult> inFlight;
        private int inFlightSample = -1;
        private long generation;
        private long inFlightGeneration = -1L;
        private final long enqueuedFrame = frameId;
        private final double renderCost;
        private long priorityFrame = -1;
        private boolean inView;

        /** 初始化请求并记录当前完整边界。
         * 目标类型决定是否采用列车专用步进。 */
        private Request(Level world, Vec3 camera, AABB bounds, BlockPos ignoredPos,
                        BlockEntity target, boolean trainRay, boolean eyeCandyRay) {
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
            this.blockers = new BlockPos[sampleCount];
            this.blockerStates = new BlockState[sampleCount];
            double x = bounds.getXsize(), y = bounds.getYsize(), z = bounds.getZsize();
            this.renderCost = Math.min(64.0D, Math.max(0.0D, 2.0D * (x * y + x * z + y * z)));
        }

        /** 相机改变时先核验已有见证在新视点是否仍遮挡。
         * 核验失败才清空进度，避免混合不同视点的遮挡结论。 */
        @SuppressWarnings("deprecation")
        private void retarget(Vec3 camera) {
            if (!this.camera.equals(camera)) {
                if (inFlight != null) {
                    int cancelledSample = inFlightSample;
                    cancelRequest(this);
                    // The sample index was advanced before submission.  Put it
                    // back so a camera move cannot skip an uncommitted ray.
                    if (cancelledSample >= 0) nextSample = Math.min(nextSample, cancelledSample);
                }
                this.unloadedPos = null;
                int kind = target != null ? 0 : trainRay ? 1 : eyeCandyRay ? EYE_CANDY_KIND : 2;
                // Revalidate saved witnesses at the new viewpoint without walking world voxels.
                boolean reusable = world != null && blockers != null && blockerStates != null
                        && blockers.length == blockerStates.length;
                for (int i = 0; i < nextSample; i++) {
                    if (!reusable || i >= blockers.length) {
                        reusable = false;
                        break;
                    }
                    if (blockers[i] == null || blockerStates[i] == null
                            || !world.hasChunkAt(blockers[i])
                            || world.getBlockState(blockers[i]) != blockerStates[i]
                            || !OcclusionRaycaster.witnessBlocksRay(world, camera,
                            sample(bounds, i, kind), blockers[i], ignoredPos)) {
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

        /** 校验请求是否仍对应当前世界、相机与目标。
         * 任一不匹配就不能复用。 */
        private boolean matches(Level world, Vec3 camera, AABB bounds, BlockEntity target) {
            return this.world == world && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }
    }

    /** 一次完成的采样结论与对应见证状态。
     * 只有匹配当前查询且未过期时可返回。 */
    private static final class Result {

        /** 结论所属世界、相机、边界及可选目标。
         * 用于阻止跨目标缓存复用。 */
        private final Level world;

        /** 完成采样时的相机位置。
         * 列车缓存要求精确匹配该位置。 */
        private final Vec3 camera;

        /** 完成采样时的目标包围盒。
         * 边界改变时结果立即失配。 */
        private final AABB bounds;

        /** 方块实体目标的弱引用。
         * 防止缓存延长卸载实体的生命周期。 */
        private final WeakReference<BlockEntity> target;

        /** 采样结论与完成时刻。
         * 时间过长后强制重新查询。 */
        private final boolean occluded;

        /** 完成采样时的纳秒时间戳。
         * 用于可见和遮挡结果的有效期。 */
        private final long completedAt;

        /** 每个遮挡样本记录的方块位置和状态。
         * 轮流验证并提供列车中心候选方块。 */
        private final BlockPos[] blockers;

        /** 各见证方块完成采样时的状态。
         * 与当前状态不同则清除结果。 */
        private final BlockState[] blockerStates;
        /** 下一个待抽查的样本索引。
         * 每帧最多检查一个见证方块。 */
        private int nextBlocker;
        /** 本结果最近的抽查帧。
         * 防止同帧重复消耗检查。 */
        private long lastValidationFrame = -1;
        /** 方块实体相机移动宽限期的起点。
         * 不用于列车的几何证明。 */
        private long movementGraceStarted;
        /** 所有已抽查见证的有效标志。
         * 一旦失效应清除结果。 */
        private boolean blockersValid = true;

        /** 保存一次完整采样及其见证集合。
         * 后续查询仍需执行匹配和有效期检查。 */
        private Result(Level world, Vec3 camera, AABB bounds, WeakReference<BlockEntity> target,
                       boolean occluded, long completedAt, BlockPos[] blockers, BlockState[] blockerStates) {
            this.world = world;
            this.camera = camera;
            this.bounds = bounds;
            this.target = target;
            this.occluded = occluded;
            this.completedAt = completedAt;
            this.blockers = blockers;
            this.blockerStates = blockerStates;
        }

        /** 每帧轮流核对一个遮挡方块状态。
         * 状态变化立即使缓存失效。 */
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

        /** 校验结果对应当前查询的全部关键参数。
         * 列车相机位置必须精确一致才能命中缓存。 */
        private boolean matches(Level world, Vec3 camera, AABB bounds, BlockEntity target) {
            return this.world == world && this.camera.equals(camera)
                    && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }

        /** 仅核对目标与边界，用于方块实体短暂重检。
         * 列车不使用移动宽限路径。 */
        private boolean matchesTarget(Level world, AABB bounds, BlockEntity target) {
            return this.world == world && this.bounds.equals(bounds)
                    && (this.target == null ? target == null : this.target.get() == target && target != null);
        }

        /** 判断结果是否仍在类别专属的时间窗内。
         * 过期后重新提交采样请求。 */
        private boolean fresh(long now) {
            return now - completedAt < (occluded ? OCCLUDED_TTL_NS : VISIBLE_TTL_NS);
        }

        /** 方块实体相机移动期间的有限宽限期。
         * 过期后必须等待新查询。 */
        private boolean movementGrace(long now) {
            if (!fresh(now)) return false;
            if (movementGraceStarted == 0L) movementGraceStarted = now;
            return now - movementGraceStarted < OCCLUDED_RECHECK_GRACE_NS;
        }

        /** 队列拥满时按键错开极短宽限期。
         * 仅在方块实体重检路径使用。 */
        private boolean overflowGrace(long now, Object key) {
            long staggeredLimit = 100_000_000L + Math.floorMod(key.hashCode(), 151) * 1_000_000L;
            return movementGraceStarted != 0L && now - movementGraceStarted < staggeredLimit;
        }
    }

    /** 量化后的通用盒体缓存键。
     * 不直接代表几何证明，查询仍会核对实际边界。 */
    private record BoxKey(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {}

    private record EyeCandyKey(long packedPos) {}
}
