package net.hulan.ivr.render_promotion.occlusion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class OcclusionRaycaster {

    private static final int MAX_STEPS = 256;
    private static final int MAX_DYNAMIC_STEPS = 1024;
    private static final int RAY_POOL_COUNT = 2;
    private static final int RAY_CORE_WORKERS = 1;
    private static final int RAY_MAX_WORKERS = 4;
    private static final int RAY_QUEUE_LIMIT = 160;
    private static final long RAY_KEEP_ALIVE_SECONDS = 10L;
    private static volatile ThreadPoolExecutor[] ASYNC_EXECUTORS = createExecutors();
    private static final AtomicInteger NEXT_EXECUTOR = new AtomicInteger();
    private static volatile boolean stopped;

    private static ThreadPoolExecutor[] createExecutors() {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[RAY_POOL_COUNT];
        for (int index = 0; index < executors.length; index++) {
            executors[index] = createExecutor(index);
        }
        return executors;
    }

    private static ThreadPoolExecutor createExecutor(int index) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(RAY_CORE_WORKERS,
                RAY_MAX_WORKERS,
                RAY_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(RAY_QUEUE_LIMIT),
                runnable -> {
                    Thread thread = new Thread(runnable, "IVR-occlusion-ray-" + index);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static synchronized void ensureRunning() {
        if (stopped) {
            return;
        }
        boolean allRunning = true;
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) {
            if (executor.isShutdown() || executor.isTerminated()) {
                allRunning = false;
                break;
            }
        }
        if (allRunning) return;
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) executor.shutdownNow();
        ASYNC_EXECUTORS = createExecutors();
        NEXT_EXECUTOR.set(0);
    }

    public static synchronized void start() {
        stopped = false;
        ensureRunning();
    }

    public static synchronized void shutdown() {
        stopped = true;
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) executor.shutdownNow();
    }

    public record RaySnapshot(Vec3 start,
                              Vec3 end,
                              BlockPos ignoredPos,
                              List<RayBlock> blocks,
                              Sight immediate,
                              int steps,
                              boolean deadlineExceeded,
                              boolean preparationFailed) {
        public RaySnapshot(Vec3 start, Vec3 end, BlockPos ignoredPos,
                           List<RayBlock> blocks, Sight immediate) {
            this(start, end, ignoredPos, blocks, immediate, 0, false, false);
        }
    }

    public record RayBlock(BlockPos position, VoxelShape shape) {}

    public enum Sight {
        BLOCKED,
        CLEAR,
        UNKNOWN,
        UNLOADED
    }

    public record TraceResult(Sight sight, BlockPos blocker) {}

    @SuppressWarnings("deprecation")
    public static RaySnapshot prepare(Level world, Vec3 start, Vec3 end,
                                      BlockPos ignoredPos, int maxSteps) {
        return prepare(world, start, end, ignoredPos, maxSteps, Long.MAX_VALUE);
    }

    @SuppressWarnings("deprecation")
    public static RaySnapshot prepare(Level world, Vec3 start, Vec3 end,
                                      BlockPos ignoredPos, int maxSteps, long deadlineNanos) {
        if (world == null || start == null || end == null) {
            return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNKNOWN, 0, false, true);
        }
        if (deadlineExceeded(deadlineNanos)) return timeoutSnapshot(start, end, ignoredPos, 0);
        Vec3 direction = end.subtract(start);
        double distance = direction.length();
        if (!(distance > 0.001D)) {
            return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.CLEAR, 0, false, false);
        }
        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);
        int stepX = direction.x >= 0.0D ? 1 : -1;
        int stepY = direction.y >= 0.0D ? 1 : -1;
        int stepZ = direction.z >= 0.0D ? 1 : -1;
        double deltaX = direction.x == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.x);
        double deltaY = direction.y == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.y);
        double deltaZ = direction.z == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.z);
        double nextX = direction.x == 0.0D ? Double.MAX_VALUE
                : deltaX * (direction.x >= 0.0D ? Math.floor(start.x) + 1.0D - start.x : start.x - Math.floor(start.x));
        double nextY = direction.y == 0.0D ? Double.MAX_VALUE
                : deltaY * (direction.y >= 0.0D ? Math.floor(start.y) + 1.0D - start.y : start.y - Math.floor(start.y));
        double nextZ = direction.z == 0.0D ? Double.MAX_VALUE
                : deltaZ * (direction.z >= 0.0D ? Math.floor(start.z) + 1.0D - start.z : start.z - Math.floor(start.z));
        ArrayList<RayBlock> blocks = new ArrayList<>();
        int steps = 0;
        try {
            while (Math.min(nextX, Math.min(nextY, nextZ)) < 1.0D) {
                if (deadlineExceeded(deadlineNanos)) {
                    return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNKNOWN, steps, true, false);
                }
                if (++steps > maxSteps) {
                    return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNKNOWN, steps, false, false);
                }
                if (nextX <= nextY && nextX <= nextZ) {
                    x += stepX;
                    nextX += deltaX;
                } else if (nextY <= nextZ) {
                    y += stepY;
                    nextY += deltaY;
                } else {
                    z += stepZ;
                    nextZ += deltaZ;
                }
                BlockPos currentPos = new BlockPos(x, y, z);
                boolean loaded = world.hasChunkAt(currentPos);
                if (deadlineExceeded(deadlineNanos)) {
                    return timeoutSnapshot(start, end, ignoredPos, steps);
                }
                if (!loaded) {
                    return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNLOADED, steps, false, false);
                }
                if (currentPos.equals(ignoredPos)) continue;
                BlockState state = world.getBlockState(currentPos);
                if (deadlineExceeded(deadlineNanos)) {
                    return timeoutSnapshot(start, end, ignoredPos, steps);
                }
                if (!state.canOcclude()) continue;
                VoxelShape shape = state.getOcclusionShape(world, currentPos);
                if (deadlineExceeded(deadlineNanos)) {
                    return timeoutSnapshot(start, end, ignoredPos, steps);
                }
                if (shape.isEmpty()) {
                    shape = state.getShape(world, currentPos, CollisionContext.empty());
                    if (deadlineExceeded(deadlineNanos)) {
                        return timeoutSnapshot(start, end, ignoredPos, steps);
                    }
                }
                if (!shape.isEmpty() && shape.min(Direction.Axis.X) <= 0.0D
                        && shape.max(Direction.Axis.X) >= 1.0D
                        && shape.min(Direction.Axis.Y) <= 0.0D
                        && shape.max(Direction.Axis.Y) >= 1.0D
                        && shape.min(Direction.Axis.Z) <= 0.0D
                        && shape.max(Direction.Axis.Z) >= 1.0D) {
                    blocks.add(new RayBlock(currentPos, shape));
                }
            }
            if (deadlineExceeded(deadlineNanos)) return timeoutSnapshot(start, end, ignoredPos, steps);
            return new RaySnapshot(start, end, ignoredPos, List.copyOf(blocks), null, steps, false, false);
        } catch (RuntimeException failure) {
            return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNKNOWN, steps, false, true);
        }
    }

    private static boolean deadlineExceeded(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0L;
    }

    private static RaySnapshot timeoutSnapshot(Vec3 start, Vec3 end, BlockPos ignoredPos, int steps) {
        return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.UNKNOWN, steps, true, false);
    }

    public static TraceResult resolve(RaySnapshot snapshot) {
        if (snapshot == null || snapshot.start() == null || snapshot.end() == null
                || snapshot.blocks() == null) return new TraceResult(Sight.UNKNOWN, null);
        if (snapshot.immediate() != null) return new TraceResult(snapshot.immediate(), null);
        for (RayBlock block : snapshot.blocks()) {
            if (Thread.currentThread().isInterrupted()) return new TraceResult(Sight.UNKNOWN, null);
            if (block == null || block.position() == null || block.shape() == null) {
                return new TraceResult(Sight.UNKNOWN, null);
            }
            if (block.shape().clip(snapshot.start(), snapshot.end(), block.position()) != null) {
                return new TraceResult(Sight.BLOCKED, block.position());
            }
        }
        return new TraceResult(Sight.CLEAR, null);
    }

    public static Future<TraceResult> submit(RaySnapshot snapshot) {
        if (snapshot == null || stopped) return null;
        ensureRunning();
        if (stopped) return null;
        ThreadPoolExecutor[] executors = ASYNC_EXECUTORS;
        int first = Math.floorMod(NEXT_EXECUTOR.getAndIncrement(), RAY_POOL_COUNT);
        for (int attempt = 0; attempt < RAY_POOL_COUNT; attempt++) {
            try {
                return executors[(first + attempt) % executors.length]
                        .submit(() -> resolve(snapshot));
            } catch (RejectedExecutionException rejected) {
            }
        }
        return null;
    }

    public static boolean isBlocked(net.minecraft.world.level.Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        Sight sight = traceSight(world, start, end, ignoredPos).sight();
        return sight == Sight.BLOCKED;
    }

    public static BlockPos firstBlocker(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return traceSight(world, start, end, ignoredPos).blocker();
    }

    public static TraceResult traceSight(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos);
    }

    public static boolean isBlockedForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                   BlockEntity target, AABB targetBounds) {
        Sight sight = traceSightForBlockEntity(world, start, end, target, targetBounds).sight();
        return sight == Sight.BLOCKED;
    }

    public static BlockPos firstBlockerForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                       BlockEntity target, AABB targetBounds) {
        return traceSightForBlockEntity(world, start, end, target, targetBounds).blocker();
    }

    public static TraceResult traceSightForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                        BlockEntity target, AABB targetBounds) {
        return trace(world, start, end, target.getBlockPos(), MAX_STEPS);
    }

    private static TraceResult trace(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos, MAX_STEPS);
    }

    public static TraceResult traceSightForTrain(Level world, Vec3 start, Vec3 end,
                                                  BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos, dynamicMaxSteps(start, end));
    }

    @SuppressWarnings("deprecation")
    public static boolean isWholeBoundsOccludedBySingleBlock(Level world, Vec3 camera,
                                                              AABB bounds, BlockPos blocker) {
        if (world == null || camera == null || bounds == null || blocker == null
                || !world.hasChunkAt(blocker)) return false;
        BlockState state = world.getBlockState(blocker);
        VoxelShape shape = state.getOcclusionShape(world, blocker);
        if (!state.canOcclude() || !state.isSolidRender(world, blocker)
                || Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.ONLY_FIRST)) return false;

        double[] eye = {camera.x, camera.y, camera.z};
        double[] min = {bounds.minX, bounds.minY, bounds.minZ};
        double[] max = {bounds.maxX, bounds.maxY, bounds.maxZ};
        double[] blockMin = {blocker.getX(), blocker.getY(), blocker.getZ()};
        double margin = 0.02D;
        for (int axis = 0; axis < 3; axis++) {
            double face;
            if (eye[axis] < blockMin[axis] - margin && min[axis] > blockMin[axis] + 1.0D + margin) {
                face = blockMin[axis];
            } else if (eye[axis] > blockMin[axis] + 1.0D + margin && max[axis] < blockMin[axis] - margin) {
                face = blockMin[axis] + 1.0D;
            } else {
                continue;
            }
            boolean covers = true;
            for (int corner = 0; corner < 8 && covers; corner++) {
                double targetAxis = (corner & (1 << axis)) == 0 ? min[axis] : max[axis];
                double t = (face - eye[axis]) / (targetAxis - eye[axis]);
                for (int other = 0; other < 3; other++) {
                    if (other == axis) continue;
                    double targetOther = (corner & (1 << other)) == 0 ? min[other] : max[other];
                    double projected = eye[other] + t * (targetOther - eye[other]);
                    if (!(projected > blockMin[other] + margin
                            && projected < blockMin[other] + 1.0D - margin)) {
                        covers = false;
                        break;
                    }
                }
            }
            if (covers) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static TraceResult trace(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos, int maxSteps) {
        Vec3 direction = end.subtract(start);
        double distance = direction.length();
        if (!(distance > 0.001D)) {
            return new TraceResult(Sight.CLEAR, null);
        }
        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);
        int stepX = direction.x >= 0.0D ? 1 : -1;
        int stepY = direction.y >= 0.0D ? 1 : -1;
        int stepZ = direction.z >= 0.0D ? 1 : -1;
        double deltaX = direction.x == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.x);
        double deltaY = direction.y == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.y);
        double deltaZ = direction.z == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.z);
        double nextX = direction.x == 0.0D ? Double.MAX_VALUE : deltaX * (direction.x >= 0.0D ? Math.floor(start.x) + 1.0D - start.x : start.x - Math.floor(start.x));
        double nextY = direction.y == 0.0D ? Double.MAX_VALUE : deltaY * (direction.y >= 0.0D ? Math.floor(start.y) + 1.0D - start.y : start.y - Math.floor(start.y));
        double nextZ = direction.z == 0.0D ? Double.MAX_VALUE : deltaZ * (direction.z >= 0.0D ? Math.floor(start.z) + 1.0D - start.z : start.z - Math.floor(start.z));
        int steps = 0;
        while (Math.min(nextX, Math.min(nextY, nextZ)) < 1.0D) {
            if (++steps > maxSteps) {
                return new TraceResult(Sight.UNKNOWN, null);
            }
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                nextY += deltaY;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }
            BlockPos currentPos = new BlockPos(x, y, z);
            if (!world.hasChunkAt(currentPos)) return new TraceResult(Sight.UNLOADED, currentPos);
            if (blocksRay(world, start, end, currentPos, ignoredPos)) {
                return new TraceResult(Sight.BLOCKED, currentPos);
            }
        }
        return new TraceResult(Sight.CLEAR, null);
    }

    private static int dynamicMaxSteps(Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        int crossings = (int) Math.ceil(Math.abs(delta.x))
                + (int) Math.ceil(Math.abs(delta.y))
                + (int) Math.ceil(Math.abs(delta.z));
        return Math.min(MAX_DYNAMIC_STEPS, Math.max(MAX_STEPS, crossings + 4));
    }

    private static boolean blocksRay(Level world, Vec3 start, Vec3 end, BlockPos currentPos,
                                     BlockPos ignoredPos) {
        if (currentPos.equals(ignoredPos)) return false;
        BlockState state = world.getBlockState(currentPos);
        if (!state.canOcclude()) return false;
        VoxelShape shape = state.getOcclusionShape(world, currentPos);
        if (shape.isEmpty()) shape = state.getShape(world, currentPos, CollisionContext.empty());
        return !shape.isEmpty() && shape.min(Direction.Axis.X) <= 0.0D
                && shape.max(Direction.Axis.X) >= 1.0D
                && shape.min(Direction.Axis.Y) <= 0.0D
                && shape.max(Direction.Axis.Y) >= 1.0D
                && shape.min(Direction.Axis.Z) <= 0.0D
                && shape.max(Direction.Axis.Z) >= 1.0D
                && shape.clip(start, end, currentPos) != null;
    }

    static boolean witnessBlocksRay(Level world, Vec3 start, Vec3 end, BlockPos blocker, BlockPos ignoredPos) {
        if (blocker == null || new AABB(blocker).contains(start)) return false;
        return blocksRay(world, start, end, blocker, ignoredPos);
    }
}
