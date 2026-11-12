package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public final class SegmentVisibility extends Visibility {

    private static final SegmentVisibility INSTANCE = new SegmentVisibility();

    public static SegmentVisibility getInstance() {
        return INSTANCE;
    }

    @Override
    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    @Override
    protected double getRenderDistance() {
        return IVRConfig.msdCatenaryRD;
    }


    private final Map<CellKey, OcclusionScheduler.ResultType> CELL_RESULTS = new java.util.HashMap<>();

    private long cellFrame = -1;

    private FrustumCache.CameraSnapshot cellView;

    private int fineAdmissions;

    public boolean shouldRenderRailQuad(double x1,
                                        double z1,
                                        double x2,
                                        double z2,
                                        double x3,
                                        double z3,
                                        double x4,
                                        double z4,
                                        double y1,
                                        double y2) {
        return shouldRenderBounds(
                Math.min(Math.min(x1, x2), Math.min(x3, x4)),
                Math.max(Math.max(x1, x2), Math.max(x3, x4)),
                Math.min(y1, y2),
                Math.max(y1, y2),
                Math.min(Math.min(z1, z2), Math.min(z3, z4)),
                Math.max(Math.max(z1, z2), Math.max(z3, z4)),
                IVRConfig.nteRailBoxRD);
    }

    public boolean shouldRenderCatenarySegment(double x1,
                                               double z1,
                                               double x2,
                                               double z2,
                                               double x3,
                                               double z3,
                                               double x4,
                                               double z4,
                                               double xs1,
                                               double zs1,
                                               double xs2,
                                               double zs2,
                                               double xs3,
                                               double zs3,
                                               double xs4,
                                               double zs4,
                                               double y1,
                                               double y2) {
        return shouldRenderBounds(
                Math.min(
                        Math.min(Math.min(x1, x2), Math.min(x3, x4)),
                        Math.min(Math.min(xs1, xs2), Math.min(xs3, xs4))),
                Math.max(
                        Math.max(Math.max(x1, x2), Math.max(x3, x4)),
                        Math.max(Math.max(xs1, xs2), Math.max(xs3, xs4))),
                Math.min(y1, y2) - 0.25D,
                Math.max(y1, y2) + 0.25D,
                Math.min(
                        Math.min(Math.min(z1, z2), Math.min(z3, z4)),
                        Math.min(Math.min(zs1, zs2), Math.min(zs3, zs4))),
                Math.max(
                        Math.max(Math.max(z1, z2), Math.max(z3, z4)),
                        Math.max(Math.max(zs1, zs2), Math.max(zs3, zs4))),
                getRenderDistance());
    }

    public boolean shouldRenderCatenaryLine(double x1,
                                            double y1,
                                            double z1,
                                            double x2,
                                            double y2,
                                            double z2) {
        return shouldRenderBounds(
                Math.min(x1, x2) - 0.25D,
                Math.max(x1, x2) + 0.25D,
                Math.min(y1, y2) - 0.25D,
                Math.max(y1, y2) + 0.25D,
                Math.min(z1, z2) - 0.25D,
                Math.max(z1, z2) + 0.25D,
                getRenderDistance());
    }

    @SuppressWarnings("deprecation")
    private boolean shouldRenderBounds(double minX,
                                       double maxX,
                                       double minY,
                                       double maxY,
                                       double minZ,
                                       double maxZ,
                                       double distance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return false;
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return false;
        }
        AABB actualBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        Level world = minecraft.level;
        BlockPos targetPos = new BlockPos(
                (int) Math.floor((minX + maxX) * 0.5D),
                (int) Math.floor((minY + maxY) * 0.5D),
                (int) Math.floor((minZ + maxZ) * 0.5D));
        if (!world.hasChunkAt(targetPos)) {
            CELL_RESULTS.clear();
            return false;
        }
        if (illegalBounds(actualBox) || !hasChunksLoaded(world, actualBox)) {
            CELL_RESULTS.clear();
            return false;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        return isRenderable(getRenderResult(world, actualBox, distance, view, box -> checkSegmentBlocked(box, view)));
    }

    private Result checkSegmentBlocked(AABB actualBox, FrustumCache.CameraSnapshot view) {
            beginCellFrame(view);
            CellKey cellKey = segmentCell(actualBox);
            OcclusionScheduler.ResultType cellResult = cellKey == null ? OcclusionScheduler.ResultType.VISIBLE : cellState(cellKey);
            OcclusionScheduler.ResultType result;
            if (cellResult == OcclusionScheduler.ResultType.OCCLUDED) {
                result = cellResult;
            } else {
                result = fineState(actualBox);
            }
            return result == OcclusionScheduler.ResultType.UNLOADED ? Result.UNLOADED
                    : result == OcclusionScheduler.ResultType.OCCLUDED ? Result.OCCLUDED
                    : result == OcclusionScheduler.ResultType.DEFERRED ? Result.DEFERRED
                    : result == OcclusionScheduler.ResultType.PENDING ? Result.PENDING : Result.VISIBLE;
    }

    private CellKey segmentCell(AABB box) {
        Vec3 center = box.getCenter();
        int x = (int) Math.floor(center.x / 2.0D);
        int y = (int) Math.floor(center.y / 2.0D);
        int z = (int) Math.floor(center.z / 2.0D);
        if (box.minX < x * 2.0D || box.maxX > x * 2.0D + 2.0D
                || box.minY < y * 2.0D || box.maxY > y * 2.0D + 2.0D
                || box.minZ < z * 2.0D || box.maxZ > z * 2.0D + 2.0D) return null;
        return new CellKey(x, y, z);
    }

    private void beginCellFrame(FrustumCache.CameraSnapshot view) {
        long frame = FrustumCache.frameId();
        if (cellFrame != frame || cellView != view) {
            CELL_RESULTS.clear();
            fineAdmissions = 0;
            cellFrame = frame;
            cellView = view;
        }
    }

    private OcclusionScheduler.ResultType cellState(CellKey key) {
        OcclusionScheduler.ResultType cached = CELL_RESULTS.get(key);
        if (cached != null) {
            return cached;
        }
        AABB cell = new AABB(key.x * 2.0D, key.y * 2.0D, key.z * 2.0D,
                key.x * 2.0D + 2.0D, key.y * 2.0D + 2.0D, key.z * 2.0D + 2.0D);
        OcclusionScheduler.ResultType result = RailVisibility.getInstance().occlusionState(cell);
        CELL_RESULTS.put(key, result);
        return result;
    }

    private OcclusionScheduler.ResultType fineState(AABB box) {
        OcclusionScheduler.ResultType cached = OcclusionScheduler.cachedBox(Minecraft.getInstance().level, box).type();
        if (cached != OcclusionScheduler.ResultType.PENDING || fineAdmissions >= 16) {
            return cached;
        }
        fineAdmissions++;
        return RailVisibility.getInstance().occlusionState(box);
    }

    private record CellKey(int x, int y, int z) {
    }
}
