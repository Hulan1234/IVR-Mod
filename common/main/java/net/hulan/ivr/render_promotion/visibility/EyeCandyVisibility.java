package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class EyeCandyVisibility extends Visibility {

    private static final EyeCandyVisibility INSTANCE = new EyeCandyVisibility();
    private static final Map<Class<?>, Optional<Method>> BOUNDS_METHODS = new ConcurrentHashMap<>();

    public static EyeCandyVisibility getInstance() {
        return INSTANCE;
    }

    protected AABB getBounds(BlockPos pos, AABB suppliedBounds) {
        return suppliedBounds;
    }

    protected double getRenderDistance() {
        return IVRConfig.nteRailBoxRD;
    }

    @SuppressWarnings("deprecation")
    public boolean shouldRender(BlockEntity entity) {
        long started = RenderDebugStats.startTiming();
        Level world = Minecraft.getInstance().level;
        if (entity == null || world == null || entity.getLevel() != world) return false;
        BlockPos pos = entity.getBlockPos();
        if (pos == null || !world.hasChunkAt(pos)) return false;
        AABB bounds = renderBounds(entity, pos);
        if (illegalBounds(bounds) || !hasChunksLoaded(world, bounds)) return false;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        Object key = OcclusionScheduler.eyeCandyKey(pos);
        RenderDebugStats.Category previous = RenderDebugStats.pushCategory(
                RenderDebugStats.Category.EYE_CANDY);
        try {
            Result result = getRenderResult(pos, world, bounds, view,
                    box -> occlusion(world, key, box));
            RenderDebugStats.record(RenderDebugStats.Category.EYE_CANDY, result,
                    RenderDebugStats.elapsedSince(started));
            return isRenderable(result);
        } finally {
            RenderDebugStats.popCategory(previous);
        }
    }

    private Result occlusion(Level world, Object key, AABB bounds) {
        OcclusionScheduler.QueryResult result = OcclusionScheduler.queryEyeCandy(
                world, key, bounds, null);
        return switch (result.type()) {
            case OCCLUDED -> Result.OCCLUDED;
            case UNLOADED -> Result.UNLOADED;
            case PENDING -> Result.PENDING;
            case DEFERRED -> Result.DEFERRED;
            case VISIBLE -> Result.VISIBLE;
        };
    }

    private AABB renderBounds(BlockEntity entity, BlockPos pos) {
        try {
            Optional<Method> optional = BOUNDS_METHODS.computeIfAbsent(entity.getClass(), type -> {
                try {
                    Method candidate = type.getMethod("getRenderBoundingBox");
                    candidate.setAccessible(true);
                    return Optional.of(candidate);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return Optional.empty();
                }
            });
            if (optional.isPresent()) {
                Method method = optional.get();
                Object value = method.invoke(entity);
                if (value instanceof AABB box && !illegalBounds(box)) return box;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return new AABB(x - 2.0D, y - 2.0D, z - 2.0D,
                x + 3.0D, y + 5.0D, z + 3.0D);
    }
}
