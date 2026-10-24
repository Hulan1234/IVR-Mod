package net.hulan.ivr.render_promotion.utils;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 兼容旧调用点的渲染优化门面。
 * 常量与委托方法集中转发到各可见性策略类。
 */
public final class TrainRenderPolicy {
    /**
     * 列车兼容入口使用的最大渲染距离。
     * 其他细分策略另有各自的阈值。
     */
    public static volatile double TRAIN_RENDER_DISTANCE = IVRConfig.DEFAULT_TRAIN_RD;

    /**
     * 禁止实例化门面类，避免产生无状态对象。
     * 兼容入口全部为静态方法。
     */
    private TrainRenderPolicy() {
    }

    /**
     * 返回兼容调用点当前使用的列车距离。
     * 保存配置后立即返回新值。
     */
    public static double getTrainRenderDistance() {
        return IVRConfig.trainRD;
    }

    /** 同步保留给旧调用点的距离字段。 */
    public static void refreshConfiguredDistances() {
        TRAIN_RENDER_DISTANCE = IVRConfig.trainRD;
    }

    /**
     * 将三个坐标分量按标记转换为相机相对位置。
     * 具体空值与相对坐标行为由向量重载处理。
     */
    public static Vec3 toCameraRelative(double x, double y, double z, boolean relative) {
        return toCameraRelative(new Vec3(x, y, z), relative);
    }

    /**
     * 已标记为相对坐标时原样返回，空值保持空值。
     * 世界坐标则减去当前主相机位置。
     */
    public static Vec3 toCameraRelative(Vec3 position, boolean relative) {
        if (position == null || relative) {
            return position;
        }
        return position.subtract(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }

    /**
     * 将方块中心坐标转换为相机相对位置。
     * 空方块位置直接返回空值。
     */
    public static Vec3 toCameraRelative(BlockPos pos) {
        return pos == null ? null : toCameraRelative(
                new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D), false);
    }

    /**
     * 用当前相机快照判断球体是否完全在视锥外。
     * 相对位置为空时直接视为视锥外。
     */
    public static boolean isOutsideFrustum(Vec3 relative, double radius) {
        if (relative == null) {
            return true;
        }
        FrustumCache.CameraSnapshot view =
                FrustumCache.capture(Minecraft.getInstance().level);
        return view.outsideFrustum(relative, radius);
    }

    /**
     * 仅在客户端世界存在时执行当前视锥测试。
     * 未进入世界时返回否，保留兼容调用点行为。
     */
    public static boolean isOutsideCurrentFrustum(Vec3 relative, double radius) {
        return Minecraft.getInstance().level != null && isOutsideFrustum(relative, radius);
    }

    /**
     * 转发单节列车的距离、视锥和遮挡策略。
     * 坐标相对性由调用者通过标记传入。
     */
    public static boolean shouldRenderTrainCar(Level world, long trainId, int carIndex, Vec3 center,
                                               float yaw, float pitch, boolean relative) {
        return TrainVisibility.getInstance().shouldRenderCar(world, trainId, carIndex, center, yaw, pitch, relative);
    }

    /**
     * 为当前列车渲染过程建立阶段筛选上下文。
     * 绘制结束后应调用清理入口释放该上下文。
     */
    public static void prepareTrainRenderMode(Level world, mtr.data.TrainClient train,
                                              Vec3 center, boolean relative) {
        TrainVisibility.getInstance().prepareRenderMode(world, train, center, relative);
    }

    /**
     * 查询当前列车上下文是否允许指定模型阶段。
     * 具体阶段策略由 TrainVisibility 决定。
     */
    public static boolean shouldRenderTrainStage(Object stage) {
        return TrainVisibility.getInstance().shouldRenderStage(stage);
    }

    /**
     * 清理线程上的列车渲染阶段上下文。
     * 避免上一列车的模式影响后续模型绘制。
     */
    public static void clearTrainRenderMode() {
        TrainVisibility.getInstance().clearRenderMode();
    }

    /**
     * 判断整列位置是否都超出可见距离。
     * 模拟期据此决定是否跳过逐车厢工作。
     */
    public static boolean isWholeTrainBeyondRenderDistance(Level world, Vec3[] positions, int spacing) {
        return TrainVisibility.getInstance().isWholeTrainBeyondDistance(world, positions, spacing);
    }

    /**
     * 转发列车车厢的遮挡查询。
     * 未知结果由策略保守处理以避免误剔除。
     */
    public static boolean shouldCullOccludedCar(Level world, String trainId, int carIndex,
                                                Vec3 center, float yaw, float pitch) {
        return TrainVisibility.getInstance().shouldCullOccludedCar(world, trainId, carIndex, center, yaw, pitch);
    }
}
