package net.hulan.ivr.util;

import mtr.data.TrainClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/**
 * 渲染优化工具类（客户端）。
 *
 * 核心思路：在 GPU 压力大的密集场景（大站台、多列车）下，
 * 通过"纯距离阈值剔除"在真正把顶点提交给 GPU 之前就跳过不必要的渲染。
 * 不做任何视锥/遮挡/身后剔除（避免误剔导致物体在屏幕边缘消失）。
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
     * 规则：≤此距离的车厢完整渲染（MTR 原模型，含内饰）；>此距离完全不渲染（纯阈值）。
     */
    public static final double TRAIN_RENDER_DISTANCE = 200.0D;

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

    /* -------------------- 公共方法（供 Mixin 调用） -------------------- */

    /**
     * 列车渲染距离（格）。
     * 纯阈值：≤此距离完整渲染；>此距离完全不渲染。
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
