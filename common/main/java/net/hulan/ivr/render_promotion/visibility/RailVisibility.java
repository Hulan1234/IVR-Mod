package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

/**
 * 为 ANTE 轨道包围盒提供可见性策略。
 * 大包围盒跳过稀疏遮挡射线，避免错误剔除。
 */
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
                : result == OcclusionScheduler.ResultType.PENDING ? Result.PENDING : Result.VISIBLE;
    }

    /**
     * 判断轨道盒体是否在已加载区域、距离及视锥范围内。
     * 通过基础判断后再执行保守的遮挡状态查询。
     */
    public boolean shouldRenderBox(AABB box) {
        Level world = Minecraft.getInstance().level;
        if (box == null || world == null) {
            return true;
        }
        BlockPos min = new BlockPos(
                (int) Math.floor(box.minX),
                (int) Math.floor(box.minY),
                (int) Math.floor(box.minZ));
        if (!hasChunkLoaded(min, world)) {
            return false;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        return isRenderable(getRenderResult(min, world, box, view, this::occlusion));
    }

    /**
     * 仅在遮挡结果明确为隐藏时剔除盒体。
     * 未完成或未知状态继续渲染。
     */
    public boolean shouldRenderOcclusionBox(AABB box) {
        return shouldRenderBox(box);
    }

    /**
     * 查询盒体遮挡状态。
     * 相机在盒内或盒体超过三格时直接视为可见。
     */
    public OcclusionScheduler.ResultType occlusionState(AABB box) {
        Level world = Minecraft.getInstance().level;
        if (box == null || world == null) {
            return OcclusionScheduler.ResultType.PENDING;
        }
        // This public occlusion-only entry is also used by segment cell queries.
        // Apply the same loading rule before the inside/large-box shortcuts.
        if (!hasChunksLoaded(world, box)) return OcclusionScheduler.ResultType.PENDING;
        Vec3 camera = FrustumCache.capture(world).cameraPosition();
        if (box.contains(camera)) {
            return OcclusionScheduler.ResultType.VISIBLE;
        }
        // A single sparse probe set cannot prove a large rail chunk is fully hidden.
        if (box.getXsize() > 3.0D || box.getYsize() > 3.0D || box.getZsize() > 3.0D) {
            return OcclusionScheduler.ResultType.VISIBLE;
        }
        OcclusionScheduler.QueryResult result = OcclusionScheduler.query(
                world, OcclusionScheduler.boxKey(box), box, null);
        return result.type();
    }
}
