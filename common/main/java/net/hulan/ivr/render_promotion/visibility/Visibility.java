package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.hulan.ksd.utils.ClientUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public abstract class Visibility {

    protected abstract AABB getBounds(BlockPos pos, AABB suppliedBounds);

    protected abstract double getRenderDistance();

    @SuppressWarnings("deprecation")
    protected final boolean hasChunkLoaded(BlockPos pos, Level world) {
        if (world == null || pos == null) return true;
        try {
            return world.hasChunkAt(pos);
        } catch (RuntimeException | LinkageError ignored) {
            return true;
        }

    }
    protected final boolean hasChunksLoaded(Level world, AABB bounds) {
        if (world == null || illegalBounds(bounds)) return true;
        double minX = Math.floor(bounds.minX / 16.0D);
        double minZ = Math.floor(bounds.minZ / 16.0D);
        double maxX = bounds.maxX > bounds.minX ? Math.ceil(bounds.maxX / 16.0D) - 1 : minX;
        double maxZ = bounds.maxZ > bounds.minZ ? Math.ceil(bounds.maxZ / 16.0D) - 1 : minZ;
        if (minX < -1875000
                || maxX > 1875000
                || minZ < -1875000
                || maxZ > 1875000) return false;
        if (exceedsRenderDistance(minX, maxX, minZ, maxZ)) {
            RenderDebugStats.recordRenderDistanceReject();
            return false;
        }
        for (int x = (int) minX; x <= (int) maxX; x++) {
            for (int z = (int) minZ; z <= (int) maxZ; z++) {
                if (!hasChunkLoaded(new BlockPos(x * 16, 0, z * 16), world)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean exceedsRenderDistance(double minX, double maxX, double minZ, double maxZ) {
        Minecraft minecraft;
        try {
            minecraft = Minecraft.getInstance();
        } catch (RuntimeException | LinkageError ignored) {
            return true;
        }
        if (minecraft == null || minecraft.options == null) return true;
        int renderDistance;
        try {
            renderDistance = ClientUtilities.getRenderDistance(minecraft.options);
        } catch (RuntimeException | LinkageError ignored) {
            return true;
        }
        long radius = Math.max(1L, renderDistance);
        if (radius > (Long.MAX_VALUE - 1L) / 2L) return true;
        long maxSpan = radius * 2L + 1L;
        long spanX = (long) maxX - (long) minX + 1L;
        long spanZ = (long) maxZ - (long) minZ + 1L;
        return spanX > maxSpan || spanZ > maxSpan;
    }
    public final Result getRenderResult(BlockPos pos,
                                        Level world,
                                        AABB suppliedBounds,
                                        FrustumCache.CameraSnapshot view,
                                        BlockedCheck blocked) {
        if (!IVRConfig.cullingEnabled) return Result.ALLOWED;
        AABB bounds = getBounds(pos, suppliedBounds);
        if (!hasChunkLoaded(pos, world)) return Result.UNLOADED;
        return getRenderResult(world, bounds, getRenderDistance(), view, blocked);
    }

    public final Result getRenderResult(Level world,
                                        AABB bounds,
                                        double distance,
                                        FrustumCache.CameraSnapshot view,
                                        BlockedCheck blocked) {
        if (!IVRConfig.cullingEnabled) return Result.ALLOWED;
        Objects.requireNonNull(blocked, "blocked");
        if (world == null || illegalBounds(bounds)) {
            return Result.ALLOWED;
        }
        if (view == null) {
            return Result.ALLOWED;
        }
        if (isOutOfRD(bounds, distance, view)) {
            RenderDebugStats.recordRenderDistanceReject();
            return Result.DISTANCE;
        }
        if (checkFrustum(bounds, view)) {
            return Result.FRUSTUM;
        }
        if (bounds.contains(view.cameraPosition())) {
            return Result.NEAR;
        }
        return Objects.requireNonNull(blocked.check(bounds), "blocked result");
    }

    protected final boolean isOutOfRD(AABB bounds,
                                      double distance,
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

    protected static boolean isRenderable(Result result) {
        return result == Result.ALLOWED
                || result == Result.NEAR
                || result == Result.VISIBLE
                || result == Result.PENDING;
    }

    protected static boolean illegalBounds(AABB box) {
        return infiniteBounds(box)
                || !(box.minX <= box.maxX)
                || !(box.minY <= box.maxY)
                || !(box.minZ <= box.maxZ);
    }

    protected static boolean infiniteBounds(AABB box) {
        return box == null
                || !Double.isFinite(box.minX) || !Double.isFinite(box.minY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxX)
                || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ);
    }

    protected static double getDistance(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

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

    public enum Result {

        ALLOWED,
        UNLOADED,
        DISTANCE,
        FRUSTUM,
        NEAR,
        VISIBLE,
        OCCLUDED,
        DEFERRED,
        PENDING,
    }
}
