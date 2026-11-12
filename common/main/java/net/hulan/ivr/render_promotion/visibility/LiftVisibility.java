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

    private final Map<Long, State> STATES = new ConcurrentHashMap<>();

    private Level cacheWorld;

    private long cacheRevision = -1;

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
        if (result.type() == OcclusionScheduler.ResultType.DEFERRED) return Result.DEFERRED;
        if (result.type() != OcclusionScheduler.ResultType.OCCLUDED) return Result.VISIBLE;
        return OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(
                world,
                view.cameraPosition(),
                bounds,
                result.witness()) ? Result.OCCLUDED : Result.VISIBLE;
    }

    private record State(FrustumCache.CameraSnapshot view, double fov, Vec3 center,
                         boolean relative, boolean visible, long frame) {
    }
}
