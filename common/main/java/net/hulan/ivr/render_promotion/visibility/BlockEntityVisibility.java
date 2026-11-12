package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.hulan.ivr.render_promotion.occlusion.OcclusionRaycaster;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class BlockEntityVisibility extends Visibility {

    private static final BlockEntityVisibility INSTANCE = new BlockEntityVisibility();

    public static BlockEntityVisibility getInstance() {
        return INSTANCE;
    }

    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    protected double getRenderDistance() {
        return IVRConfig.blockEntityRD;
    }

    private final Map<BlockEntity, State> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Class<? extends BlockEntity>, BoundsProvider> BOUNDS_PROVIDERS = new ConcurrentHashMap<>();
    private Level cacheWorld;
    private long cacheRevision = -1;

    public <T extends BlockEntity> void registerBoundsProvider(Class<T> type, Function<T, AABB> provider) {
        registerBoundsProvider(type, provider, false);
    }

    public <T extends BlockEntity> void registerStaticBoundsProvider(Class<T> type, Function<T, AABB> provider) {
        registerBoundsProvider(type, provider, true);
    }

    private <T extends BlockEntity> void registerBoundsProvider(Class<T> type,
                                                                Function<T, AABB> provider,
                                                                boolean staticBounds) {
        BOUNDS_PROVIDERS.put(type, new BoundsProvider(entity -> provider.apply(type.cast(entity)), staticBounds));
        CACHE.clear();
    }

    private State checkState(BlockEntity entity, double radius) {
        long instanceRevision = RenderDataRevision.instance(entity);
        State previous = CACHE.get(entity);
        if (previous != null && previous.instanceRevision != instanceRevision) {
            CACHE.remove(entity);
        }
        Level world = entity.getLevel();
        long revision = IVRConfig.revision();
        if (cacheWorld != world || cacheRevision != revision) {
            CACHE.clear();
            cacheWorld = world;
            cacheRevision = revision;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        long frame = FrustumCache.frameId();
        State cached = CACHE.get(entity);
        BoundsProvider provider = BOUNDS_PROVIDERS.get(entity.getClass());
        if (provider == null) {
            for (Class<?> type = entity.getClass().getSuperclass(); type != null; type = type.getSuperclass()) {
                provider = BOUNDS_PROVIDERS.get(type);
                if (provider != null) {
                    break;
                }
            }
        }
        BlockPos pos = entity.getBlockPos();
        if (!hasChunkLoaded(pos, world)) {
            CACHE.remove(entity);
            State unloaded = new State();
            unloaded.visible = false;
            unloaded.view = view;
            unloaded.radius = radius;
            unloaded.pos = pos.asLong();
            unloaded.bounds = new AABB(pos);
            unloaded.preciseBounds = false;
            return unloaded;
        }
        if (provider == null) {
            if (cached == null) {
                cached = new State();
                CACHE.put(entity, cached);
            }
            cached.visible = true;
            cached.view = view;
            cached.radius = radius;
            cached.bounds = new AABB(pos);
            cached.preciseBounds = false;
            cached.pos = pos.asLong();
            cached.blockState = null;
            return cached;
        }
        BlockState blockState = provider.staticBounds ? entity.getBlockState() : null;
        boolean reusableBounds = provider.staticBounds
                && cached != null
                && cached.preciseBounds
                && cached.pos == pos.asLong()
                && cached.blockState == blockState;
        if (cached != null
                && cached.frame == frame
                && cached.view == view
                && cached.radius == radius && reusableBounds
                && hasChunksLoaded(world, cached.bounds)) {
            return cached;
        }
        AABB bounds = reusableBounds ? cached.bounds : boundsFor(entity, provider);
        boolean preciseBounds = reusableBounds ? cached.preciseBounds : bounds != null;
        if (bounds == null) {
            if (cached == null) {
                cached = new State();
                CACHE.put(entity, cached);
            }
            cached.visible = true;
            cached.view = view;
            cached.radius = radius;
            cached.bounds = new AABB(pos);
            cached.preciseBounds = false;
            cached.pos = pos.asLong();
            cached.blockState = blockState;
            return cached;
        }
        if (cached != null && cached.frame == frame && cached.view == view && cached.radius == radius
                && (!provider.staticBounds || reusableBounds)
                && java.util.Objects.equals(cached.bounds, bounds) && hasChunksLoaded(world, bounds)) {
            return cached;
        }
        if (cached == null) {
            cached = new State();
            CACHE.put(entity, cached);
        }
        cached.bounds = bounds;
        cached.preciseBounds = preciseBounds;
        cached.frame = frame;
        cached.visible = true;
        cached.view = view;
        cached.radius = radius;
        cached.pos = pos.asLong();
        cached.blockState = blockState;
        cached.instanceRevision = instanceRevision;
        return cached;
    }

    public Result getRenderResult(BlockEntity entity, double radius) {
        if (entity == null || entity.getLevel() == null) {
            return Result.UNLOADED;
        }
        State state = checkState(entity, radius);
        if (!state.visible) {
            return Result.UNLOADED;
        }
        Level world = entity.getLevel();
        FrustumCache.CameraSnapshot view = state.view;
        BlockPos pos = entity.getBlockPos();
        AABB bounds = state.bounds;
        if (!state.preciseBounds) {
            return Result.ALLOWED;
        }
        Result result = getRenderResult(pos, world, bounds, view, box -> checkEntityBlocked(world, pos, box, entity));
        if (result == Result.UNLOADED) CACHE.remove(entity);
        return result;
    }

    private Result checkEntityBlocked(Level world, BlockPos pos, AABB bounds, BlockEntity entity) {
        OcclusionScheduler.QueryResult result =
                OcclusionScheduler.query(
                        world,
                        pos.asLong(),
                        bounds,
                        pos,
                        entity);
        if (result.type() == OcclusionScheduler.ResultType.OCCLUDED) {
            return OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(world,
                    FrustumCache.capture(world).cameraPosition(), bounds, result.witness())
                    ? Result.OCCLUDED : Result.VISIBLE;
        }
        return result.type() == OcclusionScheduler.ResultType.UNLOADED ? Result.PENDING
                : result.type() == OcclusionScheduler.ResultType.DEFERRED ? Result.DEFERRED
                : result.type() == OcclusionScheduler.ResultType.VISIBLE
                ? Result.VISIBLE : Result.PENDING;
    }

    private AABB boundsFor(BlockEntity entity, BoundsProvider provider) {
        if (provider == null) return null;
        try {
            AABB bounds = provider.function.apply(entity);
            if (infiniteBounds(bounds)) {
                return null;
            }
            return bounds;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class State extends RenderCache {

        private long instanceRevision;

        private double radius;

        private boolean preciseBounds;

        private long pos;

        private BlockState blockState;
    }

    private record BoundsProvider(Function<BlockEntity, AABB> function, boolean staticBounds) {
    }
}
