package net.hulan.ivr.util;

import mtr.client.ClientData;
import mtr.client.Config;
import mtr.mappings.UtilitiesClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 渲染优化工具类（客户端）。
 *
 * 核心思路：在 GPU 压力大的密集场景（大站台、多列车）下，
 * 通过"距离剔除 + 视锥剔除"在真正把顶点提交给 GPU 之前就跳过
 * 不必要的渲染，从而降低 draw call 数量，减少 GP ms。
 *
 * 覆盖两类目标：
 *   - 列车（TrainRenderOptimizeMixin）：≤TRAIN_RENDER_DISTANCE（300 格）完整渲染（MTR 原模型，含内饰）；
 *     >300 格或超出渲染距离或视锥外不渲染。列车不做"身后剔除"。
 *   - 方块实体（BlockEntityRenderOptimizeMixin）：距离剔除 + 身后剔除——
 *     <50 格完全渲染；>50 格或玩家身后完全不渲染（无中间档、无隔帧）。
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
     * 规则：≤此距离的车厢完整渲染（MTR 原模型，含内饰）；>此距离完全不渲染（纯阈值，无外壳分层）。
     */
    public static final double TRAIN_RENDER_DISTANCE = 300.0D;

    /**
     * 列车模拟剔除缓冲（格）。
     * TrainSimulateOptimizeMixin 中，列车路径首节点距相机超过
     * TRAIN_RENDER_DISTANCE + 此缓冲 → 完全跳过该列车的模拟与渲染。
     * 缓冲用于避免列车恰在渲染边缘时反复进入/退出模拟导致卡顿。
     */
    public static final double TRAIN_SIMULATE_BUFFER = 64.0D;

    /**
     * 半透明批次渲染距离（格）。
     * MTR 每帧对每节车厢渲染两次：不透明批次（车身主体）+ 半透明批次（车窗/灯光等）。
     * 规则：半透明批次下距离 > 此值 → 跳过半透明二次顶点生成（省约一半顶点）。
     * 可单独调低此值来进一步削减顶点数（远处车窗/灯光会消失，车身保留）。
     */
    public static final double TRANSLUCENT_RENDER_DISTANCE = 300.0D;

    /** 判断列车车厢是否在视锥外时使用的包围球半径（格）。车厢较长，用较大半径避免误剔除。 */
    public static final double CAR_RADIUS = 14.0D;

    /** 判断列车连接件/屏蔽门等辅助部件是否在视锥外时使用的包围球半径（格）。 */
    public static final double CONNECTION_RADIUS = 6.0D;

    /* -------------------- 车厢包围盒尺寸（用于 AABB 视锥判定） -------------------- */

    /** 车厢包围盒的半长（格），沿列车前进方向。 */
    private static final double CAR_HALF_LENGTH = 10.5D;

    /** 车厢包围盒的半宽（格），垂直于列车前进方向。 */
    private static final double CAR_HALF_WIDTH = 1.4D;

    /** 车厢包围盒的 Y 最小值（相对车厢中心，格）。 */
    private static final double CAR_Y_MIN = -1.1D;

    /** 车厢包围盒的 Y 最大值（相对车厢中心，格）。 */
    private static final double CAR_Y_MAX = 2.1D;

    /** 遮挡剔除启用时的最近距离（格），近处（16 格内）不做遮挡判定——近景列车应始终渲染。 */
    private static final double OCCLUSION_MIN_DISTANCE = 16.0D;

    /** 遮挡剔除启用时的最远距离（格）。 */
    private static final double OCCLUSION_MAX_DISTANCE = 320.0D;

    /**
     * 方块实体（站牌 / PSD / PID / 时钟 / 站名牌等）的最大渲染距离（格）。
     * 规则：< 此距离完全渲染；> 此距离完全不渲染（纯距离剔除，无中间档）。
     */
    public static final double BLOCK_ENTITY_RENDER_DISTANCE = 50.0D;

    /* -------------------- 内部常量（私有） -------------------- */

    /** 视锥近裁剪面距离（格），用于把相机正后方的物体剔除。 */
    private static final double NEAR_PLANE = 0.05D;

    /** 垂直方向 FOV 一半的正切值，静态初始化时解析一次（视锥计算用）。 */
    private static final double FOV_TAN_HALF = resolveFovTanHalf();

    /* -------------------- 公共方法（供 Mixin 调用） -------------------- */

    /**
     * 列车渲染距离（格）。
     * 纯阈值：≤此距离完整渲染；>此距离完全不渲染。
     * 渲染距离上限还受 isTooFar（MTR 渲染距离）约束。
     */
    public static double getTrainRenderDistance() {
        return TRAIN_RENDER_DISTANCE;
    }

    /**
     * 半透明批次渲染距离（格）。
     * 半透明批次下距离 > 此值 → 跳过半透明二次顶点生成。
     */
    public static double getTranslucentRenderDistance() {
        return TRANSLUCENT_RENDER_DISTANCE;
    }

    /** 触发方块遮挡剔除的列车数量阈值。列车数超过此值才启用遮挡射线检测（节省低负载时的开销）。 */
    public static final int OCCLUSION_TRAIN_COUNT_THRESHOLD = 5;

    /**
     * 是否启用方块遮挡剔除。
     * 列车数量超过 OCCLUSION_TRAIN_COUNT_THRESHOLD 时启用；
     * 列车少时关闭，避免射线检测的额外开销（此时渲染本身压力不大）。
     */
    public static boolean shouldUseOcclusion() {
        return ClientData.TRAINS.size() > OCCLUSION_TRAIN_COUNT_THRESHOLD;
    }

    /**
     * 判断相机相对位置是否超出 MTR 的列车渲染距离。
     * 与 MTR 自身的 shouldNotRender 逻辑保持一致（渲染距离 ×（比例 + 1））。
     */
    public static boolean isTooFar(Vec3 rel) {
        return rel == null || rel.length() > UtilitiesClient.getRenderDistance() * (Config.trainRenderDistanceRatio() + 1);
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
     * 手动实现的视锥剔除：判断相机相对位置是否在视锥之外。
     * 用相机的 yaw/pitch 构造 forward/right/up 三个基向量，
     * 再以 FOV 与屏幕宽高比构造 4 个侧面 + 1 个近裁剪面的平面，
     * 若物体中心（rel）在所有平面的"背面"且距离超过 radius，则视为不可见。
     *
     * @param rel    物体中心相对相机的坐标
     * @param radius 物体的包围球半径
     * @return true 表示在视锥外（应剔除）
     */
    public static boolean isOutsideFrustum(Vec3 rel, double radius) {
        if (rel == null) {
            return true;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double yaw = Math.toRadians(camera.getYRot());
        double pitch = Math.toRadians(camera.getXRot());
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        // 相机朝向的三个基向量
        Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        Vec3 right = new Vec3(-cosYaw, 0.0D, -sinYaw);
        Vec3 up = right.cross(forward);
        double tanVertical = FOV_TAN_HALF;
        double aspect = (double) Minecraft.getInstance().getWindow().getWidth() / (double) Minecraft.getInstance().getWindow().getHeight();
        double tanHorizontal = tanVertical * aspect;
        // 四个侧面平面 + 近裁剪面，任意一个判定在平面外即剔除
        return isOutsidePlane(right.add(forward.scale(tanHorizontal)).normalize(), 0.0D, rel, radius)
                || isOutsidePlane(forward.scale(tanHorizontal).subtract(right).normalize(), 0.0D, rel, radius)
                || isOutsidePlane(up.add(forward.scale(tanVertical)).normalize(), 0.0D, rel, radius)
                || isOutsidePlane(forward.scale(tanVertical).subtract(up).normalize(), 0.0D, rel, radius)
                || isOutsidePlane(forward, NEAR_PLANE, rel, radius);
    }

    /**
     * 判断整节车厢是否完全在视锥之外（AABB 级视锥剔除）。
     * 车厢用一个长方体（长 CAR_HALF_LENGTH、宽 CAR_HALF_WIDTH、高 CAR_Y_MIN~CAR_Y_MAX）
     * 近似，绕 Y 轴旋转 yaw 后取 8 个角点的相机相对坐标。
     * 只要**任一**角点在视锥内（或视锥与包围盒相交），就返回 false（应渲染）；
     * 只有 8 个角点**全部**在视锥外才返回 true（可剔除）。
     * 相比按中心点判定，不会误剔"中心在屏幕外但车厢边缘可见"的情况。
     *
     * @param rel 车厢中心相对相机的坐标
     * @param yaw 车厢朝向（弧度）
     * @return true 表示整个车厢都在视锥外（应剔除）
     */
    public static boolean isCarOutsideFrustum(Vec3 rel, float yaw) {
        if (rel == null) {
            return true;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double yawDeg = Math.toRadians(camera.getYRot());
        double pitch = Math.toRadians(camera.getXRot());
        double cosYawCam = Math.cos(yawDeg);
        double sinYawCam = Math.sin(yawDeg);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        Vec3 forward = new Vec3(-sinYawCam * cosPitch, -sinPitch, cosYawCam * cosPitch);
        Vec3 right = new Vec3(-cosYawCam, 0.0D, -sinYawCam);
        Vec3 up = right.cross(forward);
        double tanVertical = FOV_TAN_HALF;
        double aspect = (double) Minecraft.getInstance().getWindow().getWidth() / (double) Minecraft.getInstance().getWindow().getHeight();
        double tanHorizontal = tanVertical * aspect;
        Vec3[] planes = {
                right.add(forward.scale(tanHorizontal)).normalize(),
                forward.scale(tanHorizontal).subtract(right).normalize(),
                up.add(forward.scale(tanVertical)).normalize(),
                forward.scale(tanVertical).subtract(up).normalize(),
                forward
        };
        double[] constants = {0.0D, 0.0D, 0.0D, 0.0D, NEAR_PLANE};
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double[] corners = {
                -CAR_HALF_LENGTH, -CAR_HALF_WIDTH, CAR_HALF_LENGTH, CAR_HALF_WIDTH
        };
        // 遍历 8 个角点，只要任一角点不在任意平面外（即在视锥内）就视为可见
        for (int i = 0; i < 8; i++) {
            double lx = (i & 1) == 0 ? corners[0] : corners[2];
            double lz = (i & 2) == 0 ? corners[1] : corners[3];
            double ly = (i & 4) == 0 ? CAR_Y_MIN : CAR_Y_MAX;
            double x = lx * cosYaw + lz * sinYaw;
            double z = -lx * sinYaw + lz * cosYaw;
            Vec3 corner = new Vec3(rel.x + x, rel.y + ly, rel.z + z);
            boolean insideAll = true;
            for (int p = 0; p < planes.length; p++) {
                if (planes[p].dot(corner) - constants[p] < 0.0D) {
                    insideAll = false;
                    break;
                }
            }
            if (insideAll) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断车厢是否被方块遮挡（相机与车厢之间隔着实心方块）。
     * 用 DDA（数字微分分析）沿射线逐格遍历，确保不跳过任何被穿过的方块，
     * 即使薄墙（1 格厚）也能被检测到。
     * 一旦遇到 Material.isSolidBlocking 的方块（石头/混凝土/土等实心阻挡），
     * 即认为车厢被遮挡。
     * 距离限制：OCCLUSION_MIN_DISTANCE ~ OCCLUSION_MAX_DISTANCE（近处不判避免误剔，极远不判省开销）。
     *
     * @param world  车厢所在世界
     * @param center 车厢中心世界坐标
     * @return true 表示被遮挡（应剔除）
     */
    public static boolean isCarOccluded(Level world, Vec3 center) {
        if (world == null || center == null) {
            return false;
        }
        Vec3 camera = cameraPosition();
        Vec3 direction = center.subtract(camera);
        double distance = direction.length();
        if (distance < OCCLUSION_MIN_DISTANCE || distance > OCCLUSION_MAX_DISTANCE) {
            return false;
        }
        // DDA：逐格遍历射线穿过的每个方块
        double dx = direction.x;
        double dy = direction.y;
        double dz = direction.z;
        int x = (int) Math.floor(camera.x);
        int y = (int) Math.floor(camera.y);
        int z = (int) Math.floor(camera.z);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? Math.abs(1.0D / dx) : Double.MAX_VALUE;
        double tDeltaY = dy != 0 ? Math.abs(1.0D / dy) : Double.MAX_VALUE;
        double tDeltaZ = dz != 0 ? Math.abs(1.0D / dz) : Double.MAX_VALUE;
        double tMaxX = dx != 0 ? tDeltaX * (dx > 0 ? (Math.floor(camera.x) + 1.0D - camera.x) : (camera.x - Math.floor(camera.x))) : Double.MAX_VALUE;
        double tMaxY = dy != 0 ? tDeltaY * (dy > 0 ? (Math.floor(camera.y) + 1.0D - camera.y) : (camera.y - Math.floor(camera.y))) : Double.MAX_VALUE;
        double tMaxZ = dz != 0 ? tDeltaZ * (dz > 0 ? (Math.floor(camera.z) + 1.0D - camera.z) : (camera.z - Math.floor(camera.z))) : Double.MAX_VALUE;
        // 从相机所在方块之后开始，到车厢所在方块之前结束
        while (tMaxX < distance || tMaxY < distance || tMaxZ < distance) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (world.getBlockState(pos).getMaterial().isSolidBlocking()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断相机相对位置是否在玩家身后。
     * 用相机朝向构造 forward 向量，若 rel 与 forward 的点积 < 0，
     * 说明物体中心在相机朝向的后方（夹角超过 90°），应剔除。
     * 只判定"身后"，不涉及左右/上下的视野边界（避免屏幕边缘误剔）。
     * 注：仅用于方块实体剔除；列车不做身后剔除（只做视锥剔除）。
     *
     * @param rel 物体中心相对相机的坐标
     * @return true 表示在玩家身后（应剔除）
     */
    public static boolean isBehindCamera(Vec3 rel) {
        if (rel == null) {
            return true;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double yaw = Math.toRadians(camera.getYRot());
        double pitch = Math.toRadians(camera.getXRot());
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        // 相机朝向前方单位向量（与 isOutsideFrustum 中一致）
        Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        return rel.dot(forward) < 0.0D;
    }

    /* -------------------- 私有方法 -------------------- */

    /**
     * 解析玩家当前 FOV 的一半的正切值。
     * 通过反射读取 Options.fov（不同 MC 版本字段/方法名不同，做了兼容）。
     * 最小按 70° 兜底，避免 FOV 过小时视锥太窄。
     */
    private static double resolveFovTanHalf() {
        double fov = 70.0D;
        try {
            Field field = Options.class.getDeclaredField("fov");
            field.setAccessible(true);
            fov = field.getDouble(Minecraft.getInstance().options);
        } catch (Exception e) {
            try {
                Method method = Options.class.getMethod("fov");
                fov = (Double) method.invoke(Minecraft.getInstance().options);
            } catch (Exception ignored) {
            }
        }
        return Math.tan(Math.toRadians(Math.max(70.0D, fov) * 0.5D));
    }

    /**
     * 判断点 rel（相机相对坐标）是否在平面法线 normal 的背面（超出包围球半径）。
     * 平面方程：normal·rel - constant，若结果 < -radius 说明在平面"外面"。
     */
    private static boolean isOutsidePlane(Vec3 normal, double constant, Vec3 rel, double radius) {
        return normal.dot(rel) - constant < -radius;
    }

    /** 获取主相机在世界中的位置。 */
    private static Vec3 cameraPosition() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }
}
