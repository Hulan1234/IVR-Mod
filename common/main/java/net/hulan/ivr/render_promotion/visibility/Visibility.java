package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Instance visibility policy. Occlusion progress belongs exclusively to the shared scheduler. */
public abstract class Visibility {

    protected abstract AABB getBounds(BlockPos pos, AABB suppliedBounds);

    protected abstract double getRenderDistance();

    public enum Result {
        ALLOWED,
        UNLOADED,
        DISTANCE,
        FRUSTUM,
        NEAR,
        VISIBLE,
        OCCLUDED,
        PENDING
    }

    @SuppressWarnings("deprecation")
    protected final boolean hasChunkLoaded(BlockPos pos, Level world) {
        return world == null || pos == null || world.hasChunkAt(pos);
    }

    protected final boolean hasChunksLoaded(Level world, AABB bounds) {
        if (world == null || illegalBounds(bounds)) return true;
        double minX = Math.floor(bounds.minX / 16.0D);
        double minZ = Math.floor(bounds.minZ / 16.0D);
        // A box touching the next chunk at its exclusive maximum does not
        // occupy that chunk. Zero-width bounds still check their owning chunk.
        double maxX = bounds.maxX > bounds.minX ? Math.ceil(bounds.maxX / 16.0D) - 1 : minX;
        double maxZ = bounds.maxZ > bounds.minZ ? Math.ceil(bounds.maxZ / 16.0D) - 1 : minZ;
        // Reject unsupported scans instead of claiming their chunks are loaded.
        // The cap protects the render thread from malformed or gigantic boxes.
        if (minX < -1875000 || maxX > 1875000 || minZ < -1875000 || maxZ > 1875000
                || (maxX - minX + 1) * (maxZ - minZ + 1) > 4096) return false;
        for (int x = (int) minX; x <= (int) maxX; x++) {
            for (int z = (int) minZ; z <= (int) maxZ; z++) {
                if (!hasChunkLoaded(new BlockPos(x * 16, 0, z * 16), world)) {
                    return false;
                }
            }
        }
        return true;
    }

    public final Result getRenderResult(BlockPos pos, Level world, AABB suppliedBounds,
                                        FrustumCache.CameraSnapshot view, BlockedCheck blocked) {
        AABB bounds = getBounds(pos, suppliedBounds);
        if (!hasChunkLoaded(pos, world)) return Result.UNLOADED;
        return getRenderResult(world, bounds, getRenderDistance(), view, blocked);
    }

    /** 统一入口：距离剔除、视锥剔除，最后执行调用者对应的遮挡策略。 */
    public final Result getRenderResult(Level world, AABB bounds, double distance,
                                        FrustumCache.CameraSnapshot view, BlockedCheck blocked) {
        java.util.Objects.requireNonNull(blocked, "blocked");
        if (world == null || illegalBounds(bounds)) return Result.ALLOWED;
        if (view == null) return Result.ALLOWED;
        if (checkOutOfRD(bounds, distance, view)) return Result.DISTANCE;
        if (checkFrustum(bounds, view)) return Result.FRUSTUM;
        if (bounds.contains(view.cameraPosition())) return Result.NEAR;
        return checkBlocked(bounds, blocked);
    }

    protected final boolean checkOutOfRD(AABB bounds, double distance,
                                          FrustumCache.CameraSnapshot view) {
        Vec3 camera = view.cameraPosition();
        double dx = Math.max(bounds.minX - camera.x, Math.max(0.0D, camera.x - bounds.maxX));
        double dy = Math.max(bounds.minY - camera.y, Math.max(0.0D, camera.y - bounds.maxY));
        double dz = Math.max(bounds.minZ - camera.z, Math.max(0.0D, camera.z - bounds.maxZ));
        return Double.isFinite(distance) && getDistance(dx, dy, dz) > distance;
    }

    protected final boolean checkFrustum(AABB bounds, FrustumCache.CameraSnapshot view) {
        return FrustumCache.outsideNativeFrustum(view, bounds);
    }

    protected final Result checkBlocked(AABB bounds, BlockedCheck blocked) {
        return java.util.Objects.requireNonNull(blocked.check(bounds), "blocked result");
    }

    protected static boolean isRenderable(Result result) {
        return result != Result.DISTANCE && result != Result.FRUSTUM
                && result != Result.OCCLUDED && result != Result.UNLOADED;
    }

    protected static boolean illegalBounds(AABB box) {
        return box == null || !Double.isFinite(box.minX) || !Double.isFinite(box.minY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxX)
                || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                || !(box.minX <= box.maxX) || !(box.minY <= box.maxY) || !(box.minZ <= box.maxZ);
    }

    protected static double getDistance(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** View-only reusable data, owned by the concrete service and never storing pending proofs. */
    protected static class RenderCache {

        protected Object identity;
        protected Level world;
        protected long frame, revision;
        protected FrustumCache.CameraSnapshot view;
        protected AABB bounds;
        protected boolean visible;
    }

    @FunctionalInterface
    public interface BlockedCheck {
        Result check(AABB bounds);
    }
}
