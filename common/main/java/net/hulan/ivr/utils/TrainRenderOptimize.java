package net.hulan.ivr.utils;

import mtr.data.TrainClient;
import mtr.block.BlockPSDAPGBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 渲染优化工具类（客户端）。
 *
 * 核心思路：在 GPU 压力大的密集场景（大站台、多列车）下，
 * 通过距离、保守视锥和异步遮挡结果，在真正把顶点提交给 GPU 之前跳过不必要的渲染。
 *
 * 覆盖两类目标：
 *   - 列车（TrainRenderOptimizeMixin）：≤TRAIN_RENDER_DISTANCE（200 格）完整渲染；
 *     >200 格完全不渲染（纯距离阈值）。
 *   - 方块实体（BlockEntityRenderOptimizeMixin）：<BLOCK_ENTITY_RENDER_DISTANCE（50 格）
 *     完全渲染；≥50 格完全不渲染（纯距离阈值）。
 *   - 电梯（LiftRenderOptimizeMixin）：复用列车距离阈值。
 *
 * 所有方法均为静态，供各个 Mixin 注入点复用。
 */
public final class TrainRenderOptimize {

    /** 工具类私有构造器，禁止实例化。 */
    private TrainRenderOptimize() {
    }

    /* -------------------- 可调参数（公共常量） -------------------- */

    /**
     * 列车渲染距离（格）。
     * 规则：≤此距离的车厢完整渲染（MTR 原模型，含内饰）；>此距离完全不渲染。
     */
    public static final double TRAIN_RENDER_DISTANCE = 300.0D; // 设置列车距离剔除阈值。

    /** 列车车厢视锥判断使用的保守半径。 */
    public static final double TRAIN_VISUAL_CULL_RADIUS = 24.0D; // 为列车视锥判断提供保守包围半径。

    /** 列车连接件和屏障使用的保守半径。 */
    public static final double TRAIN_AUXILIARY_CULL_RADIUS = 32.0D; // 为连接件和屏障保留更大的可见范围。

    /**
     * 列车模拟剔除缓冲（格）。
     * TrainSimulateOptimizeMixin 中，列车路径首节点距相机超过
     * TRAIN_RENDER_DISTANCE + 此缓冲 → 完全跳过该列车的模拟与渲染。
     * 缓冲用于避免列车恰在渲染边缘时反复进入/退出模拟导致卡顿。
     */
    public static final double TRAIN_SIMULATE_BUFFER = 64.0D;

    /**
     * 方块实体（站牌 / PSD / PID / 时钟 / 站名牌等）的最大渲染距离（格）。
     * 规则：< 此距离完全渲染；≥ 此距离完全不渲染（纯距离剔除）。
     */
    public static final double BLOCK_ENTITY_RENDER_DISTANCE = 50.0D;

    /** 无法读取客户端视场角时使用的保守视场角。 */
    private static final double FALLBACK_FOV_TAN_HALF = Math.tan(Math.toRadians(55.0D)); // 计算默认视场角的一半正切值。
    private static final long OCCLUSION_REFRESH_NANOS = 150_000_000L; // 限制遮挡检测提交频率，避免每帧重复计算。
    private static final long OCCLUSION_RESULT_MAX_AGE_NANOS = 200_000_000L; // 限制遮挡结果的有效时间。
    private static final ExecutorService OCCLUSION_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivr-train-visibility"); // 创建专用的可见性计算线程。
        thread.setDaemon(true); // 防止线程阻止客户端退出。
        return thread; // 返回配置完成的后台线程。
    }); // 初始化遮挡计算线程池。
    private static final Map<String, OcclusionState> OCCLUSION_STATES = new ConcurrentHashMap<>(); // 保存列车遮挡计算结果。
    private static final Map<String, Long> OCCLUSION_REQUESTS = new ConcurrentHashMap<>(); // 保存列车最近一次计算请求时间。
    private static final long BLOCK_ENTITY_VISIBILITY_REFRESH_NANOS = 100_000_000L; // 限制方块实体视锥计算频率。
    private static final long BLOCK_ENTITY_VISIBILITY_MAX_AGE_NANOS = 250_000_000L; // 限制方块实体视锥结果有效时间。
    private static final Map<Long, BlockEntityVisibilityState> BLOCK_ENTITY_VISIBILITY_STATES = new ConcurrentHashMap<>(); // 保存方块实体视锥结果。
    private static final Map<Long, Long> BLOCK_ENTITY_VISIBILITY_REQUESTS = new ConcurrentHashMap<>(); // 保存方块实体最近请求时间。

    /* -------------------- 公共方法（供 Mixin 调用） -------------------- */

    /**
     * 列车渲染距离（格）。
     * ≤此距离完整渲染；>此距离完全不渲染。
     */
    public static double getTrainRenderDistance() {
        return TRAIN_RENDER_DISTANCE;
    }

    /**
     * 将世界坐标转为相机相对坐标。
     *
     * @param x        世界 X 坐标
     * @param y        世界 Y 坐标
     * @param z        世界 Z 坐标
     * @param relative 入参是否已经是相对坐标（玩家坐在列车里时为 true）
     */
    public static Vec3 toCameraRelative(double x, double y, double z, boolean relative) {
        if (relative) {
            return new Vec3(x, y, z);
        }
        Vec3 cameraPosition = cameraPosition();
        return new Vec3(x - cameraPosition.x, y - cameraPosition.y, z - cameraPosition.z);
    }

    /**
     * 将世界坐标向量转为相机相对坐标。
     *
     * @param pos       世界坐标
     * @param relative  入参是否已经是相对坐标
     */
    public static Vec3 toCameraRelative(Vec3 pos, boolean relative) {
        if (pos == null) {
            return null;
        }
        if (relative) {
            return pos;
        }
        return pos.subtract(cameraPosition());
    }

    /**
     * 将方块坐标转为相机相对坐标（取方块中心点，供方块实体剔除使用）。
     *
     * @param pos 世界方块坐标
     * @return 方块中心相对相机的坐标；pos 为 null 时返回 null
     */
    public static Vec3 toCameraRelative(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D).subtract(cameraPosition());
    }

    /**
     * 仅当保守包围球完全位于某个视锥平面外时返回 true。
     * 由于 BlockEntity 基类没有跨版本统一的渲染包围盒接口，这里使用包围球进行判断。
     * 半径取大可以宁可少剔除，也避免错误隐藏仍然可见的对象。
     */
    public static boolean isOutsideFrustum(Vec3 rel, double radius) {
        if (rel == null) { // 缺少相对位置时按不可见处理。
            return true; // 返回需要剔除的结果。
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double yaw = camera.getYRot(); // 读取相机水平角度。
        double pitch = camera.getXRot(); // 读取相机俯仰角度。
        double tanVertical = getFovTanHalf(); // 读取当前视场角。
        double aspect = (double) Minecraft.getInstance().getWindow().getWidth()
                / Math.max(1.0D, Minecraft.getInstance().getWindow().getHeight()); // 根据窗口尺寸计算宽高比。
        return isOutsideFrustumSnapshot(rel, radius, yaw, pitch, tanVertical, aspect); // 使用当前相机快照执行视锥判断。
    }

    private static boolean isOutsideFrustumSnapshot(Vec3 rel, double radius, double yawDegrees, double pitchDegrees, double tanVertical, double aspect) {
        double yaw = Math.toRadians(yawDegrees); // 将水平角度转换为弧度。
        double pitch = Math.toRadians(pitchDegrees); // 将俯仰角度转换为弧度。
        double cosYaw = Math.cos(yaw); // 计算相机水平角度的余弦值。
        double sinYaw = Math.sin(yaw); // 计算相机水平角度的正弦值。
        double cosPitch = Math.cos(pitch); // 计算相机俯仰角度的余弦值。
        double sinPitch = Math.sin(pitch); // 计算相机俯仰角度的正弦值。
        Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch); // 构造相机前方向量。
        Vec3 right = new Vec3(-cosYaw, 0.0D, -sinYaw); // 构造相机右方向量。
        Vec3 up = right.cross(forward); // 构造相机上方向量。
        double tanHorizontal = tanVertical * aspect; // 根据宽高比计算水平视场范围。
        return isOutsidePlane(right.add(forward.scale(tanHorizontal)).normalize(), 0.0D, rel, radius) // 检查右侧视锥平面。
                || isOutsidePlane(forward.scale(tanHorizontal).subtract(right).normalize(), 0.0D, rel, radius) // 检查左侧视锥平面。
                || isOutsidePlane(up.add(forward.scale(tanVertical)).normalize(), 0.0D, rel, radius) // 检查上侧视锥平面。
                || isOutsidePlane(forward.scale(tanVertical).subtract(up).normalize(), 0.0D, rel, radius) // 检查下侧视锥平面。
                || isOutsidePlane(forward, 0.05D, rel, radius); // 检查相机前方的近裁剪面。
    }

    /**
     * 只返回仍然有效的已完成视锥结果。视锥计算使用不可变快照异步执行，后台线程不访问 Minecraft 对象。
     * 等待中或过期的结果都会继续渲染方块实体。
     */
    public static boolean shouldCullBlockEntity(BlockPos pos, Vec3 rel, double radius) {
        if (pos == null || rel == null) { // 缺少位置时保守地保留方块实体。
            return false;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Camera camera = minecraft.gameRenderer.getMainCamera();
        final Vec3 cameraPosition = camera.getPosition(); // 捕获相机位置快照。
        final float cameraYaw = camera.getYRot(); // 捕获相机水平角度快照。
        final float cameraPitch = camera.getXRot(); // 捕获相机俯仰角度快照。
        final long key = pos.asLong(); // 使用方块坐标作为缓存键。
        final long now = System.nanoTime(); // 记录本次判定时间。
        final BlockEntityVisibilityState state = BLOCK_ENTITY_VISIBILITY_STATES.get(key); // 读取该方块实体最近一次视锥结果。
        if (state != null
                && now - state.completedAt < BLOCK_ENTITY_VISIBILITY_MAX_AGE_NANOS
                && state.relative.distanceToSqr(rel) < 0.25D
                && state.cameraPosition.distanceToSqr(cameraPosition) < 0.25D
                && angleDifferenceDegrees(state.cameraYaw, cameraYaw) < 2.0F
                && angleDifferenceDegrees(state.cameraPitch, cameraPitch) < 2.0F
                && state.cull) { // 只复用仍与当前视角和位置匹配的剔除结果。
            return true; // 使用异步线程已经确认的剔除结果。
        }

        final Long previousRequest = BLOCK_ENTITY_VISIBILITY_REQUESTS.get(key); // 读取最近一次异步请求时间。
        if (previousRequest == null || now - previousRequest >= BLOCK_ENTITY_VISIBILITY_REFRESH_NANOS) {
            final long requestAt = now; // 标记本次异步请求的版本。
            BLOCK_ENTITY_VISIBILITY_REQUESTS.put(key, requestAt); // 记录最新请求，淘汰旧任务结果。
            final double tanVertical = getFovTanHalf(); // 在主线程捕获视场角快照。
            final double aspect = (double) minecraft.getWindow().getWidth()
                    / Math.max(1.0D, minecraft.getWindow().getHeight()); // 在主线程捕获窗口宽高比。
            OCCLUSION_EXECUTOR.execute(() -> { // 将纯数学视锥计算交给后台线程。
                final boolean cull = isOutsideFrustumSnapshot(rel, radius, cameraYaw, cameraPitch, tanVertical, aspect); // 计算方块实体是否完全在视锥外。
                if (BLOCK_ENTITY_VISIBILITY_REQUESTS.get(key) != null
                        && BLOCK_ENTITY_VISIBILITY_REQUESTS.get(key) == requestAt) { // 只提交仍然是最新请求的结果。
                    BLOCK_ENTITY_VISIBILITY_STATES.put(key, new BlockEntityVisibilityState(
                            rel, cameraPosition, cameraYaw, cameraPitch, cull, System.nanoTime())); // 保存最新视锥计算结果。
                } // 丢弃已经过期的异步计算结果。
            }); // 提交方块实体视锥计算任务。
        } // 仅在达到刷新间隔时创建新任务。
        return false; // 未得到有效剔除结果时继续渲染。
    }

    private static final class BlockEntityVisibilityState {

        private final Vec3 relative; // 保存提交任务时的相机相对位置。
        private final Vec3 cameraPosition; // 保存提交任务时的相机世界位置。
        private final float cameraYaw; // 保存提交任务时的相机水平角度。
        private final float cameraPitch; // 保存提交任务时的相机俯仰角度。
        private final boolean cull; // 保存视锥判定结果。
        private final long completedAt; // 保存结果完成时间。

        private BlockEntityVisibilityState(Vec3 relative, Vec3 cameraPosition, float cameraYaw, float cameraPitch, boolean cull, long completedAt) {
            this.relative = relative; // 记录相对位置快照。
            this.cameraPosition = cameraPosition; // 记录相机位置快照。
            this.cameraYaw = cameraYaw; // 记录相机水平角度快照。
            this.cameraPitch = cameraPitch; // 记录相机俯仰角度快照。
            this.cull = cull; // 记录是否应该剔除。
            this.completedAt = completedAt; // 记录结果完成时间。
        }
    }

    /** 使用全部角点构造保守包围球后执行视锥判断。 */
    public static boolean isOutsideFrustum(Vec3[] corners, boolean relative) {
        if (corners == null || corners.length == 0) { // 没有角点时不进行剔除。
            return false;
        }
        Vec3 center = new Vec3(0.0D, 0.0D, 0.0D); // 初始化角点中心。
        int count = 0; // 统计有效角点数量。
        for (Vec3 corner : corners) {
            if (corner != null) {
                center = center.add(corner); // 累加角点坐标。
                count++; // 增加有效角点计数。
            }
        }
        if (count == 0) { // 没有有效角点时保守地保留渲染。
            return false;
        }
        center = center.scale(1.0D / count); // 计算角点中心。
        double radius = 0.0D; // 初始化包围球半径。
        for (Vec3 corner : corners) {
            if (corner != null) {
                radius = Math.max(radius, corner.distanceTo(center)); // 找到能覆盖所有角点的半径。
            }
        }
        return isOutsideFrustum(toCameraRelative(center, relative), radius + 1.0D); // 对完整包围球执行视锥测试。
    }

    /**
     * 返回最近完成的遮挡结果，并在结果过期时提交新的计算任务。
     * 后台线程只接收不可变的布尔快照；世界访问留在渲染线程，结果汇总采用异步非阻塞方式。
     */
    public static boolean shouldCullOccludedCar(net.minecraft.world.level.Level world, String trainId, int carIndex, Vec3 center, float yaw, float pitch) {
        if (world == null || trainId == null || center == null) { // 缺少遮挡检测所需数据时保守地保留列车。
            return false; // 返回不隐藏列车的结果。
        }
        final String key = trainId + ":" + carIndex; // 使用列车编号和车厢编号区分缓存结果。
        final long now = System.nanoTime(); // 记录当前检测时间。
        final Vec3 camera = cameraPosition(); // 捕获当前相机位置。
        final Camera currentCamera = Minecraft.getInstance().gameRenderer.getMainCamera(); // 获取当前主相机。
        final float cameraYaw = currentCamera.getYRot(); // 捕获相机水平角度。
        final float cameraPitch = currentCamera.getXRot(); // 捕获相机俯仰角度。
        final OcclusionState state = OCCLUSION_STATES.get(key); // 读取上一次遮挡结果。
        final Long requestAt = OCCLUSION_REQUESTS.get(key); // 读取最近一次检测请求时间。
        if (state != null
                && now - state.completedAt < OCCLUSION_RESULT_MAX_AGE_NANOS
                && (requestAt == null || requestAt <= state.completedAt)
                && state.center.distanceToSqr(center) < 0.25D
                && state.camera.distanceToSqr(camera) < 0.25D
                && angleDifference(state.yaw, yaw) < 2.0F
                && angleDifference(state.pitch, pitch) < 2.0F
                && angleDifferenceDegrees(state.cameraYaw, cameraYaw) < 2.0F
                && angleDifferenceDegrees(state.cameraPitch, cameraPitch) < 2.0F
                && state.occluded) { // 仅复用仍然匹配当前列车和相机状态的遮挡结果。
            return true; // 复用仍然匹配当前状态的完整遮挡结果。
        }
        final Long previousRequest = OCCLUSION_REQUESTS.get(key);
        if (previousRequest == null || now - previousRequest >= OCCLUSION_REFRESH_NANOS) { // 到达刷新间隔后才重新检测遮挡。
            OCCLUSION_REQUESTS.put(key, now); // 标记新的遮挡检测请求。
            final boolean[] blockedSamples = captureOcclusionSnapshot(world, camera, center, yaw); // 在主线程读取方块状态快照。
            OCCLUSION_EXECUTOR.execute(() -> { // 异步汇总不可变的遮挡快照。
                boolean occluded = true; // 假定车厢完全遮挡，直到发现可见采样点。
                for (boolean blocked : blockedSamples) { // 检查车厢的全部采样点。
                    if (!blocked) {
                        occluded = false; // 任一采样点可见即可证明车厢没有完全遮挡。
                        break; // 停止检查剩余采样点。
                    }
                }
                OcclusionState previous = OCCLUSION_STATES.get(key); // 读取上一次结果用于连续性判断。
                boolean sameView = previous != null
                        && previous.center.distanceToSqr(center) < 0.25D
                        && previous.camera.distanceToSqr(camera) < 0.25D
                        && angleDifference(previous.yaw, yaw) < 2.0F
                        && angleDifference(previous.pitch, pitch) < 2.0F
                        && angleDifferenceDegrees(previous.cameraYaw, cameraYaw) < 2.0F
                        && angleDifferenceDegrees(previous.cameraPitch, cameraPitch) < 2.0F; // 判断前后两次快照是否仍处于同一状态。
                int occlusionStreak = occluded && sameView && previous.rawOccluded
                        ? previous.occlusionStreak + 1
                        : occluded ? 1 : 0; // 计算连续完整遮挡次数。
                OCCLUSION_STATES.put(key, new OcclusionState(
                        center,
                        camera,
                        yaw,
                        pitch,
                        cameraYaw,
                        cameraPitch,
                        occluded,
                        occlusionStreak >= 3,
                        occlusionStreak,
                        System.nanoTime())); // 保存本次遮挡结果和连续计数。
            }); // 提交遮挡结果计算任务。
        }
        return false;
    }

    private static float angleDifference(float first, float second) {
        float difference = (first - second) % (float) (Math.PI * 2.0D); // 计算弧度角的周期差值。
        if (difference > Math.PI) {
            difference -= (float) (Math.PI * 2.0D); // 将差值归一化到最短旋转方向。
        } else if (difference < -Math.PI) {
            difference += (float) (Math.PI * 2.0D); // 将负向差值归一化到最短旋转方向。
        }
        return Math.abs(difference); // 返回弧度差的绝对值。
    }

    private static float angleDifferenceDegrees(float first, float second) {
        float difference = (first - second) % 360.0F; // 计算角度的周期差值。
        if (difference > 180.0F) {
            difference -= 360.0F; // 将角度差归一化到最短旋转方向。
        } else if (difference < -180.0F) {
            difference += 360.0F; // 将负向角度差归一化到最短旋转方向。
        }
        return Math.abs(difference); // 返回角度差的绝对值。
    }

    private static boolean[] captureOcclusionSnapshot(net.minecraft.world.level.Level world, Vec3 camera, Vec3 center, float yaw) {
        // JonModelTrainRenderer 传入的列车朝向单位是弧度。
        double cos = Math.cos(yaw); // 计算列车朝向的余弦值。
        double sin = Math.sin(yaw); // 计算列车朝向的正弦值。
        double[] xSamples = {-12.0D, -6.0D, 0.0D, 6.0D, 12.0D}; // 设置车厢长度方向采样位置。
        double[] ySamples = {-1.5D, 0.0D, 1.5D}; // 设置车厢高度方向采样位置。
        double[] zSamples = {-1.5D, -0.75D, 0.0D, 0.75D, 1.5D}; // 设置车厢宽度方向采样位置。
        boolean[] blocked = new boolean[xSamples.length * ySamples.length * zSamples.length]; // 创建采样结果数组。
        int index = 0; // 初始化采样结果下标。
        for (double localX : xSamples) { // 遍历车厢长度方向采样点。
            for (double localY : ySamples) { // 遍历车厢高度方向采样点。
                for (double localZ : zSamples) { // 遍历车厢宽度方向采样点。
                    Vec3 sample = center.add(localX * cos - localZ * sin, localY, localX * sin + localZ * cos); // 将局部采样点旋转到世界坐标。
                    blocked[index++] = isRayBlocked(world, camera, sample); // 检查相机到采样点的射线是否被遮挡。
                }
            }
        }
        return blocked;
    }

    private static boolean isRayBlocked(net.minecraft.world.level.Level world, Vec3 start, Vec3 end) {
        Vec3 direction = end.subtract(start); // 计算射线方向和长度向量。
        double distance = direction.length(); // 计算射线总长度。
        if (!(distance > 0.001D)) {
            return false; // 起点和终点重合时视为没有遮挡。
        }
        int x = (int) Math.floor(start.x); // 获取射线起点所在方块的 X 坐标。
        int y = (int) Math.floor(start.y); // 获取射线起点所在方块的 Y 坐标。
        int z = (int) Math.floor(start.z); // 获取射线起点所在方块的 Z 坐标。
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
        while (Math.min(nextX, Math.min(nextY, nextZ)) < 1.0D) {
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
            net.minecraft.world.level.block.state.BlockState state = world.getBlockState(new BlockPos(x, y, z)); // 读取射线经过的方块状态。
            // APG/PSD 面板在列车遮挡判断中按透明设施处理。
            // 不继续穿过面板检查后方方块，避免把面板后的实体方块错误当成遮挡物。
            // 射线遇到面板后立即将当前采样点视为可见。
            if (state.getBlock() instanceof BlockPSDAPGBase) {
                return false; // 半透明设施不阻挡视线，也不继续检查其后方方块。
            }
            // 玻璃和其他不启用遮挡面的方块不能隐藏列车。
            if (state.canOcclude() && state.getMaterial().isSolidBlocking()) {
                return true; // 发现不透明遮挡方块。
            }
        }
        return false; // 射线到达车厢前没有发现遮挡方块。
    }

    private static final class OcclusionState {

        private final Vec3 center; // 保存车厢中心位置快照。
        private final Vec3 camera; // 保存相机位置快照。
        private final float yaw; // 保存列车朝向快照。
        private final float pitch; // 保存列车俯仰快照。
        private final float cameraYaw; // 保存相机水平角度快照。
        private final float cameraPitch; // 保存相机俯仰角度快照。
        private final boolean rawOccluded; // 保存本次原始遮挡判断。
        private final boolean occluded; // 保存是否达到隐藏条件。
        private final int occlusionStreak; // 保存连续遮挡次数。
        private final long completedAt; // 保存遮挡结果完成时间。

        private OcclusionState(Vec3 center, Vec3 camera, float yaw, float pitch, float cameraYaw, float cameraPitch, boolean rawOccluded, boolean occluded, int occlusionStreak, long completedAt) {
            this.center = center; // 记录车厢中心快照。
            this.camera = camera; // 记录相机位置快照。
            this.yaw = yaw; // 记录列车朝向快照。
            this.pitch = pitch; // 记录列车俯仰快照。
            this.cameraYaw = cameraYaw; // 记录相机水平角度快照。
            this.cameraPitch = cameraPitch; // 记录相机俯仰角度快照。
            this.rawOccluded = rawOccluded; // 记录原始遮挡结果。
            this.occluded = occluded; // 记录最终隐藏资格。
            this.occlusionStreak = occlusionStreak; // 记录连续遮挡次数。
            this.completedAt = completedAt; // 记录结果完成时间。
        }
    }

    private static double getFovTanHalf() {
        try { // 尝试读取当前客户端的视场角设置。
            java.lang.reflect.Field fovField = Minecraft.getInstance().options.getClass().getDeclaredField("fov"); // 获取视场角字段。
            fovField.setAccessible(true); // 允许访问客户端选项字段。
            Object value = fovField.get(Minecraft.getInstance().options); // 读取视场角配置值。
            if (value instanceof Number) {
                return Math.tan(Math.toRadians(Math.max(70.0D, ((Number) value).doubleValue()) * 0.5D)); // 返回视场角一半的正切值。
            }
        } catch (Exception ignored) { // 字段不可用时使用保守默认值。
        }
        return FALLBACK_FOV_TAN_HALF; // 返回默认视场角一半的正切值。
    }

    private static boolean isOutsidePlane(Vec3 normal, double constant, Vec3 rel, double radius) {
        return normal.dot(rel) - constant < -radius; // 判断包围球是否完全位于平面外侧。
    }

    /**
     * 反射读取 Train（父类）的 path 字段。
     * 父类字段无法通过 @Shadow 在 TrainClient 目标上可靠定位，改用反射。
     *
     * @param trainClient 列车实例（TrainClient 继承 Train）
     * @return 列车路径列表；失败时返回 null
     */
    @SuppressWarnings("unchecked")
    public static List<mtr.path.PathData> getTrainPath(TrainClient trainClient) {
        try {
            Field field = TrainClient.class.getSuperclass().getDeclaredField("path");
            field.setAccessible(true);
            return (List<mtr.path.PathData>) field.get(trainClient);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反射读取 Lift（父类）的 currentPositionX/Y/Z 字段。
     * 父类字段无法通过 @Shadow 在 LiftClient 目标上可靠定位，改用反射。
     *
     * @param liftClient 电梯实例（LiftClient 继承 Lift）
     * @return [x, y, z]；失败时返回 null
     */
    public static double[] getLiftPosition(mtr.data.LiftClient liftClient) {
        try {
            Class<?> clazz = liftClient.getClass().getSuperclass();
            double x = clazz.getDeclaredField("currentPositionX").getDouble(liftClient);
            double y = clazz.getDeclaredField("currentPositionY").getDouble(liftClient);
            double z = clazz.getDeclaredField("currentPositionZ").getDouble(liftClient);
            return new double[]{x, y, z};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断玩家是否乘坐在这列列车上（玩家的 UUID 是否在 Train.ridingEntities 乘客集合中）。
     * 通过反射读取父类 Train 的 protected 字段 ridingEntities。
     * 用于 TrainSimulateOptimizeMixin：玩家乘坐的列车永不跳过模拟，保证整列始终渲染。
     *
     * @param trainClient 列车实例（TrainClient 继承 Train）
     * @return true 表示玩家正在乘坐这列列车
     */
    @SuppressWarnings("unchecked")
    public static boolean isPlayerOnTrain(TrainClient trainClient) {
        try {
            if (trainClient == null) {
                return false;
            }
            java.util.UUID playerUuid = Minecraft.getInstance().player.getUUID();
            Field field = TrainClient.class.getSuperclass().getDeclaredField("ridingEntities");
            field.setAccessible(true);
            Set<java.util.UUID> riding = (Set<java.util.UUID>) field.get(trainClient);
            return riding != null && riding.contains(playerUuid);
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取主相机在世界中的位置。 */
    private static Vec3 cameraPosition() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }
}
