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

/** 提供遮挡判定所需的世界射线步进和完整投影证明。
 * 遇到未加载区块或预算不足返回未知，由调用方按可见处理。 */
public final class OcclusionRaycaster {

    /** 一般对象的最大体素步进次数。
     * 超限不能证明遮挡，返回未知。 */
    private static final int MAX_STEPS = 256;

    /** 长车厢射线的硬上限。
     * 按线段长度扩大预算，但仍限制极端输入。 */
    private static final int MAX_DYNAMIC_STEPS = 1024;

    /** Bounded dynamic ray workers: two pools provide immediate shunting while
     * each pool grows only under queue pressure. */
    private static final int RAY_POOL_COUNT = 2;
    private static final int RAY_CORE_WORKERS = 1;
    private static final int RAY_MAX_WORKERS = 4;
    private static final int RAY_QUEUE_LIMIT = 64;
    private static final long RAY_KEEP_ALIVE_SECONDS = 30L;

    /**
     * 只处理已经从客户端世界复制出来的几何快照。
     * Level、BlockEntity 和其它客户端状态绝不跨线程访问。
     */
    private static volatile ThreadPoolExecutor[] ASYNC_EXECUTORS = createExecutors();
    private static final AtomicInteger NEXT_EXECUTOR = new AtomicInteger();
    private static volatile boolean lifecycleStopped;

    private static ThreadPoolExecutor[] createExecutors() {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[RAY_POOL_COUNT];
        for (int index = 0; index < executors.length; index++) {
            executors[index] = createExecutor(index);
        }
        return executors;
    }

    private static ThreadPoolExecutor createExecutor(int index) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(RAY_CORE_WORKERS, RAY_MAX_WORKERS,
                RAY_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(RAY_QUEUE_LIMIT), runnable -> {
            Thread thread = new Thread(runnable, "IVR-occlusion-ray-" + index);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Recreate workers after a client/world teardown. */
    public static synchronized void ensureRunning() {
        if (lifecycleStopped) return;
        boolean allRunning = true;
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) {
            if (executor.isShutdown() || executor.isTerminated()) {
                allRunning = false;
                break;
            }
        }
        if (allRunning) return;
        // Replace the whole set atomically.  A partially stopped pool must not
        // remain reachable from the round-robin submit path.
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) executor.shutdownNow();
        ASYNC_EXECUTORS = createExecutors();
        NEXT_EXECUTOR.set(0);
    }

    /** Allow a new client world to use the worker set after clearLevel. */
    public static synchronized void start() {
        lifecycleStopped = false;
        ensureRunning();
    }

    /** Explicitly stop idle and queued work when Minecraft closes. */
    public static synchronized void shutdown() {
        lifecycleStopped = true;
        for (ThreadPoolExecutor executor : ASYNC_EXECUTORS) executor.shutdownNow();
    }

    /** 一条射线在渲染线程采集的不可变数据。 */
    public record RaySnapshot(Vec3 start, Vec3 end, BlockPos ignoredPos,
                              List<RayBlock> blocks, Sight immediate) {}

    /** 射线经过的候选遮挡体；VoxelShape 是不可变几何数据。 */
    public record RayBlock(BlockPos position, VoxelShape shape) {}

    /** 射线的三种结论。
     * UNKNOWN 表示不能安全剔除。 */
    public enum Sight {
        BLOCKED,
        CLEAR,
        UNKNOWN,
        UNLOADED
    }

    /** 射线结论及首个遮挡方块。
     * 只有 BLOCKED 才带可用候选方块。 */
    public record TraceResult(Sight sight, BlockPos blocker) {}

    /**
     * 在客户端线程读取世界并复制射线路径。
     * 形状相交计算延后到后台；未加载区块和超长射线立即返回保守状态。
     */
    @SuppressWarnings("deprecation")
    public static RaySnapshot prepare(Level world, Vec3 start, Vec3 end,
                                      BlockPos ignoredPos, int maxSteps) {
        Vec3 direction = end.subtract(start);
        double distance = direction.length();
        if (!(distance > 0.001D)) {
            return new RaySnapshot(start, end, ignoredPos, List.of(), Sight.CLEAR);
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
        while (Math.min(nextX, Math.min(nextY, nextZ)) < 1.0D) {
            if (++steps > maxSteps) {
                return new RaySnapshot(start, end, ignoredPos, List.copyOf(blocks), Sight.UNKNOWN);
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
            if (!world.hasChunkAt(currentPos)) {
                return new RaySnapshot(start, end, ignoredPos, List.copyOf(blocks), Sight.UNLOADED);
            }
            if (currentPos.equals(ignoredPos)) continue;
            BlockState state = world.getBlockState(currentPos);
            if (!state.canOcclude()) continue;
            VoxelShape shape = state.getOcclusionShape(world, currentPos);
            if (shape.isEmpty()) shape = state.getShape(world, currentPos, CollisionContext.empty());
            if (!shape.isEmpty() && shape.min(Direction.Axis.X) <= 0.0D
                    && shape.max(Direction.Axis.X) >= 1.0D
                    && shape.min(Direction.Axis.Y) <= 0.0D
                    && shape.max(Direction.Axis.Y) >= 1.0D
                    && shape.min(Direction.Axis.Z) <= 0.0D
                    && shape.max(Direction.Axis.Z) >= 1.0D) {
                blocks.add(new RayBlock(currentPos, shape));
            }
        }
        return new RaySnapshot(start, end, ignoredPos, List.copyOf(blocks), null);
    }

    /** 在后台线程解析快照；无客户端世界访问。 */
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

    /** 将纯快照提交给有界后台队列；队列满时返回 null，调用方应保守放行。 */
    public static Future<TraceResult> submit(RaySnapshot snapshot) {
        if (snapshot == null || lifecycleStopped) return null;
        ensureRunning();
        if (lifecycleStopped) return null;
        ThreadPoolExecutor[] executors = ASYNC_EXECUTORS;
        int first = Math.floorMod(NEXT_EXECUTOR.getAndIncrement(), RAY_POOL_COUNT);
        for (int attempt = 0; attempt < RAY_POOL_COUNT; attempt++) {
            try {
                return executors[(first + attempt) % executors.length]
                        .submit(() -> resolve(snapshot));
            } catch (RejectedExecutionException rejected) {
                // Try the other bounded queue immediately.  The caller never
                // runs the ray on the render thread when both are saturated.
            }
        }
        return null;
    }

    /** 兼容旧调用方的布尔遮挡查询。
     * 未知结果视为无遮挡。 */
    public static boolean isBlocked(net.minecraft.world.level.Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        Sight sight = traceSight(world, start, end, ignoredPos).sight();
        return sight == Sight.BLOCKED;
    }

    /** 返回射线的首个遮挡方块。
     * CLEAR 或 UNKNOWN 时返回空值。 */
    public static BlockPos firstBlocker(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return traceSight(world, start, end, ignoredPos).blocker();
    }

    /** 普通目标射线查询。
     * 调用方应将 UNKNOWN 当作需要渲染。 */
    public static TraceResult traceSight(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos);
    }

    /** 方块实体的兼容布尔查询。
     * 忽略目标自身方块，未知状态保守放行。 */
    public static boolean isBlockedForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                   BlockEntity target, AABB targetBounds) {
        Sight sight = traceSightForBlockEntity(world, start, end, target, targetBounds).sight();
        return sight == Sight.BLOCKED;
    }

    /** 查找方块实体之前的首个遮挡方块。
     * 目标自身方块不参与遮挡。 */
    public static BlockPos firstBlockerForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                       BlockEntity target, AABB targetBounds) {
        return traceSightForBlockEntity(world, start, end, target, targetBounds).blocker();
    }

    /** 查询到方块实体采样点的视线状态。
     * 输入的目标用于排除自身方块。 */
    public static TraceResult traceSightForBlockEntity(Level world, Vec3 start, Vec3 end,
                                                        BlockEntity target, AABB targetBounds) {
        return trace(world, start, end, target.getBlockPos(), MAX_STEPS);
    }

    /** 普通射线使用固定步进上限。
     * 具体方块测试由共享步进函数完成。 */
    private static TraceResult trace(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos, MAX_STEPS);
    }

    /** 列车射线按长度提高步进上限。
     * 仍由调度器控制每帧射线数量。 */
    public static TraceResult traceSightForTrain(Level world, Vec3 start, Vec3 end,
                                                  BlockPos ignoredPos) {
        return trace(world, start, end, ignoredPos, dynamicMaxSteps(start, end));
    }

    /** 用一个当前完整不透明方块证明目标包围盒全部被挡住。
     * 八个角点的投影都须落在方块面内部，否则按可见处理。 */
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

    /** 沿世界体素逐格步进至目标。
     * 超出步数或遇到未加载区块返回 UNKNOWN。 */
    @SuppressWarnings("deprecation")
    private static TraceResult trace(Level world, Vec3 start, Vec3 end, BlockPos ignoredPos, int maxSteps) {
        Vec3 direction = end.subtract(start); // 计算射线方向和长度向量。
        double distance = direction.length(); // 计算射线总长度。
        if (!(distance > 0.001D)) {
            return new TraceResult(Sight.CLEAR, null);
        }
        int x = (int) Math.floor(start.x); // 获取射线起点所在方块的 X 坐标。
        int y = (int) Math.floor(start.y); // 获取射线起点所在方块的 Y 坐标。
        int z = (int) Math.floor(start.z); // 获取射线起点所在方块的 Z 坐标。
        // Do not test the start voxel: the camera can be inside a solid block
        // while still having a valid view through the block's clipped faces.
        int stepX = direction.x >= 0.0D ? 1 : -1; // 设置 X 方向步进。
        int stepY = direction.y >= 0.0D ? 1 : -1; // 设置 Y 方向步进。
        int stepZ = direction.z >= 0.0D ? 1 : -1; // 设置 Z 方向步进。
        double deltaX = direction.x == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.x); // 计算穿过一个 X 方块的归一化步长。
        double deltaY = direction.y == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.y); // 计算穿过一个 Y 方块的归一化步长。
        double deltaZ = direction.z == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / direction.z); // 计算穿过一个 Z 方块的归一化步长。
        double nextX = direction.x == 0.0D ? Double.MAX_VALUE : deltaX * (direction.x >= 0.0D ? Math.floor(start.x) + 1.0D - start.x : start.x - Math.floor(start.x)); // 计算下一次穿过 X 边界的参数。
        double nextY = direction.y == 0.0D ? Double.MAX_VALUE : deltaY * (direction.y >= 0.0D ? Math.floor(start.y) + 1.0D - start.y : start.y - Math.floor(start.y)); // 计算下一次穿过 Y 边界的参数。
        double nextZ = direction.z == 0.0D ? Double.MAX_VALUE : deltaZ * (direction.z >= 0.0D ? Math.floor(start.z) + 1.0D - start.z : start.z - Math.floor(start.z)); // 计算下一次穿过 Z 边界的参数。
        // nextX/Y/Z 是归一化射线参数，射线终点对应 t=1，而不是世界距离值。
        int steps = 0;
        while (Math.min(nextX, Math.min(nextY, nextZ)) < 1.0D) {
            if (++steps > maxSteps) {
                return new TraceResult(Sight.UNKNOWN, null);
            }
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX; // 沿 X 方向进入下一个方块。
                nextX += deltaX; // 推进 X 方向边界参数。
            } else if (nextY <= nextZ) {
                y += stepY; // 沿 Y 方向进入下一个方块。
                nextY += deltaY; // 推进 Y 方向边界参数。
            } else {
                z += stepZ; // 沿 Z 方向进入下一个方块。
                nextZ += deltaZ; // 推进 Z 方向边界参数。
            }
            BlockPos currentPos = new BlockPos(x, y, z); // 构造射线当前经过的方块坐标。
            if (!world.hasChunkAt(currentPos)) return new TraceResult(Sight.UNLOADED, currentPos);
            if (blocksRay(world, start, end, currentPos, ignoredPos)) {
                return new TraceResult(Sight.BLOCKED, currentPos);
            }
        }
        return new TraceResult(Sight.CLEAR, null);
    }

    /** 从线段三个轴的长度估计所需步数。
     * 保留硬上限防止异常坐标消耗无界时间。 */
    private static int dynamicMaxSteps(Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        int crossings = (int) Math.ceil(Math.abs(delta.x))
                + (int) Math.ceil(Math.abs(delta.y))
                + (int) Math.ceil(Math.abs(delta.z));
        return Math.min(MAX_DYNAMIC_STEPS, Math.max(MAX_STEPS, crossings + 4));
    }

    /** 判断当前方块是否真正挡住射线。
     * 非遮挡形状和未命中的方块均不算见证。 */
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

    static boolean witnessBlocksRay(Level world, Vec3 start, Vec3 end, BlockPos blocker,
                                    BlockPos ignoredPos) {
        if (blocker == null || new AABB(blocker).contains(start)) return false;
        return blocksRay(world, start, end, blocker, ignoredPos);
    }
}
