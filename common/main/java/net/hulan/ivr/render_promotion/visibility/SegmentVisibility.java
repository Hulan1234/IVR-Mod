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

/**
 * 对已生成的轨道与接触网片段执行保守可见性判断。
 * 结合区块视锥缓存、距离限制及按帧复用的遮挡结果。
 */
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


    /**
     * 当前帧两格单元的遮挡查询结果。
     * 帧或视角改变时清空此映射。
     */
    private final Map<CellKey, OcclusionScheduler.ResultType> CELL_RESULTS = new java.util.HashMap<>();

    // 单元遮挡缓存所属的渲染帧编号。
    // 编号变化时清空本帧单元结果。
    private long cellFrame = -1;

    // 单元遮挡缓存所属的相机快照。
    // 视角变化时即使同帧也清空结果。
    private FrustumCache.CameraSnapshot cellView;

    /**
     * 本帧已准入的细粒度新查询次数。
     * 达到 16 次后只读取现有缓存。
     */
    private int fineAdmissions;

    /**
     * 将 MTR 轨道四边形角点转换成可见性边界。
     * 使用完整角点生成保守边界。
     */
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

    /**
     * 综合 MSD 接触网两组角点生成完整边界。
     * 上下高度略微外扩，降低细线段误剔除风险。
     */
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

    /** 过滤 MSD 普通及过渡接触网的单段导线。 */
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

    /**
     * 依次检查有限边界、区块视锥、距离和实际片段遮挡。
     * 不确定的遮挡结果保持可见，只有明确遮挡时剔除。
     */
    @SuppressWarnings("deprecation")
    private boolean shouldRenderBounds(double minX,
                                       double maxX,
                                       double minY,
                                       double maxY,
                                       double minZ,
                                       double maxZ,
                                       double distance) {
        if (Minecraft.getInstance().level == null) {
            return true;
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return true;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(Minecraft.getInstance().level);
        AABB actualBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        Level world = Minecraft.getInstance().level;
        BlockPos targetPos = new BlockPos(
                (int) Math.floor((minX + maxX) * 0.5D),
                (int) Math.floor((minY + maxY) * 0.5D),
                (int) Math.floor((minZ + maxZ) * 0.5D));
        if (!world.hasChunkAt(targetPos)) {
            CELL_RESULTS.clear();
            return false;
        }
        // Each callback supplies its own render distance (ANTE rail vs. MSD catenary).
        // Do not silently replace it with the singleton's default distance.
        return isRenderable(getRenderResult(world, actualBox, distance, view, box -> checkSegmentBlocked(box, view)));
    }

    private Result checkSegmentBlocked(AABB actualBox, FrustumCache.CameraSnapshot view) {
            // A cell result can only prove that this segment is hidden.
            // Otherwise the actual segment box must still be queried.
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
                    : result == OcclusionScheduler.ResultType.PENDING ? Result.PENDING : Result.VISIBLE;
    }

    /**
     * 仅在片段完全位于同一个两格单元内时返回单元键。
     * 跨单元片段直接进入实际盒体查询，避免错误共享结果。
     */
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

    /**
     * 在帧编号或相机快照变化时清空单元结果。
     * 同时重置本帧细粒度查询准入数。
     */
    private void beginCellFrame(FrustumCache.CameraSnapshot view) {
        long frame = FrustumCache.frameId();
        if (cellFrame != frame || cellView != view) {
            CELL_RESULTS.clear();
            fineAdmissions = 0;
            cellFrame = frame;
            cellView = view;
        }
    }

    /**
     * 查询两格单元的遮挡状态并在本帧缓存。
     * 仅单元确认隐藏可直接证明其内部片段隐藏。
     */
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

    /**
     * 优先读取实际片段盒体的遮挡缓存。
     * 待处理状态受每帧 16 次新查询限额约束。
     */
    private OcclusionScheduler.ResultType fineState(AABB box) {
        OcclusionScheduler.ResultType cached = OcclusionScheduler.cachedBox(Minecraft.getInstance().level, box).type();
        if (cached != OcclusionScheduler.ResultType.PENDING || fineAdmissions >= 16) {
            return cached;
        }
        fineAdmissions++;
        return RailVisibility.getInstance().occlusionState(box);
    }

    /**
     * 三维两格单元键，用于本帧遮挡共享。
     * 片段必须完全落在单元内部才使用此键。
     */
    private record CellKey(int x, int y, int z) {
    }
}
