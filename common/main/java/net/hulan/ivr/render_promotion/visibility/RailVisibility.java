package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public final class RailVisibility extends Visibility {

    private static final RailVisibility INSTANCE = new RailVisibility();

    public static RailVisibility getInstance() {
        return INSTANCE;
    }

    @Override
    protected AABB getBounds(BlockPos pos, AABB box) {
        return box;
    }

    @Override
    protected double getRenderDistance() {
        return IVRConfig.nteRailBoxRD;
    }

    private Result occlusion(AABB bounds) {
        OcclusionScheduler.ResultType result = occlusionState(bounds);
        return result == OcclusionScheduler.ResultType.UNLOADED ? Result.UNLOADED
                : result == OcclusionScheduler.ResultType.OCCLUDED ? Result.OCCLUDED
                : result == OcclusionScheduler.ResultType.DEFERRED ? Result.DEFERRED
                : result == OcclusionScheduler.ResultType.PENDING ? Result.PENDING : Result.VISIBLE;
    }

    public boolean shouldRenderBox(AABB box) {
        if (box == null || illegalBounds(box) || infiniteBounds(box)
                || !(box.minX < box.maxX) || !(box.minY < box.maxY) || !(box.minZ < box.maxZ)) {
            return false;
        }
        Level world = Minecraft.getInstance().level;
        if (world == null) return false;
        BlockPos min = new BlockPos(
                (int) Math.floor(box.minX),
                (int) Math.floor(box.minY),
                (int) Math.floor(box.minZ));
        if (!hasChunkLoaded(min, world)) {
            return false;
        }
        if (!hasChunksLoaded(world, box)) {
            return false;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        return isRenderable(getRenderResult(min, world, box, view, this::occlusion));
    }

    public boolean shouldRenderOcclusionBox(AABB box) {
        return shouldRenderBox(box);
    }

    public OcclusionScheduler.ResultType occlusionState(AABB box) {
        if (box == null || illegalBounds(box) || infiniteBounds(box)
                || !(box.minX < box.maxX) || !(box.minY < box.maxY) || !(box.minZ < box.maxZ)) {
            return OcclusionScheduler.ResultType.PENDING;
        }
        Level world = Minecraft.getInstance().level;
        if (world == null) {
            return OcclusionScheduler.ResultType.PENDING;
        }
        if (!hasChunksLoaded(world, box)) return OcclusionScheduler.ResultType.PENDING;
        Vec3 camera = FrustumCache.capture(world).cameraPosition();
        if (box.contains(camera)) {
            return OcclusionScheduler.ResultType.VISIBLE;
        }
        OcclusionScheduler.QueryResult result = OcclusionScheduler.query(
                world, OcclusionScheduler.boxKey(box), box, null);
        return result.type();
    }
}
