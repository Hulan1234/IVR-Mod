package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionRaycaster;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存电梯的距离、视锥和遮挡可见性结论。
 * 世界切换会清理旧缓存；遮挡快照有独立的时间预算。
 */
public final class LiftVisibility extends Visibility {

    private static final LiftVisibility INSTANCE = new LiftVisibility();

    public static LiftVisibility getInstance() {
        return INSTANCE;
    }

    @Override
    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    @Override
    protected double getRenderDistance() {
        return IVRConfig.trainRD;
    }

    /**
     * 按电梯 ID 缓存距离与视锥结果。
     * 相机快照或电梯位置变化时重新判断。
     */
    private final Map<Long, State> STATES = new ConcurrentHashMap<>();

    /**
     * 当前视角缓存所属的世界对象。
     * 世界改变时清除电梯视锥和遮挡缓存。
     */
    private Level cacheWorld;

    // 当前 16 毫秒遮挡预算窗口的起点。
    // 窗口过期时重置采样次数。
    // 当前预算窗口已执行的遮挡快照次数。
    // 达到一份后优先复用短期旧结果。
    private long cacheRevision = -1;

    /**
     * 按当前视角、距离与电梯位置判断是否绘制。
     * 参数不全时保守允许；相机或电梯移动后重新计算。
     */
    public boolean shouldRender(Level world, long liftId, Vec3 center, boolean relative) {
        if (world == null || center == null) {
            return true;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        long revision = IVRConfig.revision();
        long frame = FrustumCache.frameId();
        if (cacheWorld != world || cacheRevision != revision) {
            STATES.clear();
            cacheWorld = world;
            cacheRevision = revision;
        }
        Vec3 absoluteCenter = relative ? center.add(view.cameraPosition()) : center;
        AABB bounds = new AABB(
                absoluteCenter.x - 8,
                absoluteCenter.y - 8,
                absoluteCenter.z - 8,
                absoluteCenter.x + 8,
                absoluteCenter.y + 8,
                absoluteCenter.z + 8);
        BlockPos pos = new BlockPos(
                (int) Math.floor(absoluteCenter.x),
                (int) Math.floor(absoluteCenter.y),
                (int) Math.floor(absoluteCenter.z));
        if (!hasChunkLoaded(pos, world)) {
            STATES.remove(liftId);
            return false;
        }
        State cached = STATES.get(liftId);
        double fov = FrustumCache.fovTanHalf();
        if (cached != null && cached.frame == frame && cached.view.equals(view) && cached.fov == fov
                && cached.relative == relative && cached.center.distanceToSqr(center) < 0.0001D) {
            return cached.visible;
        }
        Result gate = getRenderResult(pos, world, bounds, view,
                box -> relative ? Result.VISIBLE : checkLiftBlocked(world, liftId, box, view));
        boolean visible = isRenderable(gate);
        STATES.put(liftId, new State(view, fov, center, relative, visible, frame));
        return visible;
    }

    private Result checkLiftBlocked(Level world, long liftId, AABB bounds,
                                   FrustumCache.CameraSnapshot view) {
        OcclusionScheduler.QueryResult result = OcclusionScheduler.query(world, "lift:" + liftId, bounds, null);
        if (result.type() == OcclusionScheduler.ResultType.UNLOADED) return Result.PENDING;
        if (result.type() == OcclusionScheduler.ResultType.PENDING) return Result.PENDING;
        if (result.type() != OcclusionScheduler.ResultType.OCCLUDED) return Result.VISIBLE;
        // A sparse set around the center cannot hide an eight-block-radius
        // model. Require a current proof covering the complete model box.
        return OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(
                world,
                view.cameraPosition(),
                bounds,
                result.witness()) ? Result.OCCLUDED : Result.VISIBLE;
    }

    /**
     * 保存电梯视锥结果所依赖的视角与位置。
     * 参数相同或足够接近时复用可见性结论。
     */
    private record State(FrustumCache.CameraSnapshot view, double fov, Vec3 center,
                         boolean relative, boolean visible, long frame) {
    }
}
