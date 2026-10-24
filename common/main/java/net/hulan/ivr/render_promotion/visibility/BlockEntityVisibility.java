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

/**
 * 在通用方块实体渲染入口统一执行视锥与遮挡判定。
 * 精确模型边界由注册器提供，缺失边界时保守保留渲染。
 */
public final class BlockEntityVisibility extends Visibility {

    private static final BlockEntityVisibility INSTANCE = new BlockEntityVisibility();

    public static BlockEntityVisibility getInstance() {
        return INSTANCE;
    }

    @Override
    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    @Override
    protected double getRenderDistance() {
        return IVRConfig.blockEntityRD;
    }

    /**
     * 按实体弱键保存最近的视角判断状态。
     * 世界切换或边界提供器注册时清空。
     */
    private final Map<BlockEntity, State> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    // 按实体具体类型注册的模型边界函数。
    // 注册新类型时清除已有视角状态缓存。
    private final Map<Class<? extends BlockEntity>, BoundsProvider> BOUNDS_PROVIDERS = new ConcurrentHashMap<>();
    // 当前实体视角缓存所对应的世界。
    // 世界对象切换时清空旧实体状态。
    private Level cacheWorld;
    // 记录视角缓存使用的配置版本，保存新配置后立即重算可见性。
    private long cacheRevision = -1;

    /**
     * 注册每次查询都可能变化的完整模型世界空间边界。
     * 新注册提供器会清除已有实体结果以避免旧边界。
     */
    public <T extends BlockEntity> void registerBoundsProvider(Class<T> type, Function<T, AABB> provider) {
        registerBoundsProvider(type, provider, false);
    }

    /**
     * 注册仅随方块状态与位置变化的模型边界。
     * 可复用缓存中的边界，但注册时仍清空旧结果。
     */
    public <T extends BlockEntity> void registerStaticBoundsProvider(Class<T> type, Function<T, AABB> provider) {
        registerBoundsProvider(type, provider, true);
    }

    /**
     * 包装类型安全的边界函数并写入注册表。
     * 静态标记决定缓存是否能跨视角复用边界。
     */
    private <T extends BlockEntity> void registerBoundsProvider(Class<T> type,
                                                                       Function<T, AABB> provider,
                                                                       boolean staticBounds) {
        BOUNDS_PROVIDERS.put(type, new BoundsProvider(entity -> provider.apply(type.cast(entity)), staticBounds));
        CACHE.clear();
    }

    /**
     * 取得或更新实体在当前视角下的可见性状态。
     * 动态边界每次核验；静态边界按位置和方块状态复用。
     */
    private State checkState(BlockEntity entity, double radius) {
        long instanceRevision = RenderDataRevision.instance(entity);
        State previous = CACHE.get(entity);
        if (previous != null && previous.instanceRevision != instanceRevision) CACHE.remove(entity);
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
                if (provider != null) break;
            }
        }
        BlockPos pos = entity.getBlockPos();
        // A block entity whose owning chunk is not loaded cannot be rendered
        // reliably. Intermediate chunks are still handled conservatively by
        // the ray caster and remain UNKNOWN/PENDING.
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
        // An unregistered renderer has no trustworthy model bounds. Keep it
        // visible for every camera position; the unit fallback must never be
        // fed through distance/frustum culling on a later cached frame.
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
        boolean reusableBounds = provider.staticBounds && cached != null && cached.preciseBounds
                && cached.pos == pos.asLong() && cached.blockState == blockState;
        if (cached != null && cached.frame == frame && cached.view == view && cached.radius == radius && reusableBounds
                && hasChunksLoaded(world, cached.bounds)) {
            return cached;
        }
        AABB bounds = reusableBounds ? cached.bounds : boundsFor(entity, provider);
        boolean preciseBounds = reusableBounds ? cached.preciseBounds : bounds != null;
        if (bounds == null) {
            // Unknown model bounds are never used for custom distance/frustum culling.
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

    /**
     * 将实体判定分类为视锥外、遮挡、近处或可绘制状态。
     * 缺少精确边界时不以方块大小推断遮挡。
     */
    public Result getRenderResult(BlockEntity entity, double radius) {
        if (entity == null || entity.getLevel() == null) {
            return Result.ALLOWED;
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
        Result result = getRenderResult(pos, world, bounds, view,
                box -> checkEntityBlocked(world, pos, box, entity));
        if (result == Result.UNLOADED) CACHE.remove(entity);
        return result;
    }

    private Result checkEntityBlocked(Level world, BlockPos pos, AABB bounds, BlockEntity entity) {
        OcclusionScheduler.QueryResult result = OcclusionScheduler.query(
                world, pos.asLong(), bounds, pos, entity);
        if (result.type() == OcclusionScheduler.ResultType.OCCLUDED) {
            return OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(world,
                    FrustumCache.capture(world).cameraPosition(), bounds, result.witness())
                    ? Result.OCCLUDED : Result.VISIBLE;
        }
        return result.type() == OcclusionScheduler.ResultType.UNLOADED ? Result.PENDING
                : result.type() == OcclusionScheduler.ResultType.VISIBLE
                ? Result.VISIBLE : Result.PENDING;
    }

    /**
     * 调用注册的边界函数并验证各坐标为有限数。
     * 函数缺失或抛异常时返回空值以保守处理。
     */
    private AABB boundsFor(BlockEntity entity, BoundsProvider provider) {
        if (provider == null) return null;
        try {
            AABB bounds = provider.function.apply(entity);
            if (bounds == null || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY)
                    || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxX)
                    || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)) {
                return null;
            }
            return bounds;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 保存实体边界、方块状态及对应视角的判断结果。
     * 该对象可在同一实体的后续可见性查询中更新。
     */
    private static final class State extends RenderCache {

        private long instanceRevision;

        // 此次判断使用的近似模型半径。
        // 半径改变会使旧视角结果失效。
        private double radius;

        // 边界是否来自精确模型提供器。
        // 为假时不会依据方块盒执行遮挡剔除。
        private boolean preciseBounds;

        /**
         * 实体位置的压缩坐标。
         * 静态边界缓存据此判断位置变化。
         */
        private long pos;

        // 静态边界对应的方块状态。
        // 状态变化会触发模型边界重新计算。
        private BlockState blockState;
    }

    /**
     * 包装类型适配后的边界函数和静态边界标记。
     * 标记决定实体视角状态是否复用旧边界。
     */
    private record BoundsProvider(Function<BlockEntity, AABB> function, boolean staticBounds) {
    }
}
