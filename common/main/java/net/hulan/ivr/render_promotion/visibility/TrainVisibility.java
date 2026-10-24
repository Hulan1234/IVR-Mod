package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.cache.TrainLengthCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionRaycaster;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.lang.ref.WeakReference;

/** 管理 MTR 车厢、模型阶段及整列车的可见性。
 * 默认车厢边界及可选模型边界均须通过当前遮挡证明。 */
public final class TrainVisibility extends Visibility {

    private static final TrainVisibility INSTANCE = new TrainVisibility();

    public static TrainVisibility getInstance() {
        return INSTANCE;
    }

    @Override
    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    @Override protected double getRenderDistance() {
        return IVRConfig.trainRD;
    }

    /** 默认车厢从原点向上的高度。
     * 俯仰旋转时用于计算竖直外接范围。 */
    private static final double DEFAULT_HEIGHT = 4.0D;

    /** 默认车厢沿横向的半宽。
     * 对应原采样左右各 2 格的范围。 */
    private static final double DEFAULT_HALF_WIDTH = 2.0D;

    /** 给默认车体边界留出少量模型及插值余量。
     * 自定义模型可用边界提供者替换该近似范围。 */
    private static final double DEFAULT_BOUNDS_PADDING = 0.5D;

    /** 车厢粗判所用保守半径。
     * 大于实际车体以减少边缘视角漏渲染。 */
    private static final double BOUNDING_RADIUS = 32.0D;

    /** 按列车和车厢索引保存连续遮挡确认。
     * 世界切换或结果不确定时清除，避免旧状态直接隐藏车厢。 */
    private final Map<Object, OcclusionConfirmation> OCCLUSION_CONFIRMATIONS = new HashMap<>();

    /** 确认缓存所属世界；世界对象改变时整体失效。
     * 不跨维度沿用遮挡结论。 */
    private Level confirmationWorld;

    /** 可选的完整车体边界来源。
     * 未登记或未给出边界时使用默认车体范围。 */
    private volatile CarBoundsProvider carBoundsProvider;

    /** 当前渲染调用的车体阶段模式。
     * 逐车厢设置并在返回或取消时恢复完整模式。 */
    private final ThreadLocal<RenderMode> RENDER_MODE = ThreadLocal.withInitial(() -> RenderMode.FULL);

    /** 提供包含全部车体模型顶点的世界坐标边界。
     * 返回 null 时改用默认车体范围。 */
    public interface CarBoundsProvider {
        /** 返回当前姿态下的完整世界坐标包围盒。
         * 未知模型可返回 null，使用默认范围。 */
        AABB bounds(mtr.data.TrainClient train, int carIndex, Vec3 center, float yaw, float pitch);
    }

    /** 登记具有完整模型几何信息的边界提供者。
     * 传入 null 可撤销并恢复默认范围。 */
    public void registerCarBoundsProvider(CarBoundsProvider provider) {
        carBoundsProvider = provider;
        OCCLUSION_CONFIRMATIONS.clear();
    }

    /** 根据相机距离和乘车状态选择车体阶段。
     * 输入缺失或距离越界时回退到完整模式。 */
    public void prepareRenderMode(Level world, mtr.data.TrainClient train, Vec3 center, boolean relative) {
        if (world == null || train == null || center == null) {
            RENDER_MODE.set(RenderMode.FULL);
            return;
        }
        Vec3 rel = relative ? center : FrustumCache.capture(world).relative(center);
        double distance = rel.lengthSqr();
        double renderDistance = IVRConfig.trainRD;
        double fullDistance = Math.min(75.0D, renderDistance * 0.375D);
        double exteriorDistance = Math.max(fullDistance, Math.min(150.0D, renderDistance * 0.75D));
        if (!Double.isFinite(distance) || relative
                || Minecraft.getInstance().player != null && train.isPlayerRiding(Minecraft.getInstance().player)
                || distance <= fullDistance * fullDistance || distance > renderDistance * renderDistance) {
            RENDER_MODE.set(RenderMode.FULL);
        } else {
            RENDER_MODE.set(distance <= exteriorDistance * exteriorDistance
                    ? RenderMode.EXTERIOR_ONLY : RenderMode.FAR_EXTERIOR);
        }
    }

    /** 决定当前模型阶段是否绘制。
     * 未识别的阶段默认放行，避免版本新增阶段消失。 */
    public boolean shouldRenderStage(Object stage) {
        if (!(stage instanceof mtr.model.ModelTrainBase.RenderStage)) {
            return true;
        }
        return switch (RENDER_MODE.get()) {
            case FULL -> true;
            case FAR_EXTERIOR -> stage != mtr.model.ModelTrainBase.RenderStage.INTERIOR
                    && stage != mtr.model.ModelTrainBase.RenderStage.INTERIOR_TRANSLUCENT
                    && stage != mtr.model.ModelTrainBase.RenderStage.LIGHTS
                    && stage != mtr.model.ModelTrainBase.RenderStage.ALWAYS_ON_LIGHTS;
            case EXTERIOR_ONLY -> stage != mtr.model.ModelTrainBase.RenderStage.INTERIOR
                    && stage != mtr.model.ModelTrainBase.RenderStage.INTERIOR_TRANSLUCENT;
        };
    }

    /** 结束车厢渲染后恢复默认模式。
     * 防止模式泄漏到下一个车厢或列车。 */
    public void clearRenderMode() {
        RENDER_MODE.set(RenderMode.FULL);
    }

    /** 车体模型阶段的三种绘制策略。
     * 仅用于当前渲染线程中的一次车厢调用。 */
    private enum RenderMode { FULL, EXTERIOR_ONLY, FAR_EXTERIOR }

    /** 先做距离与视锥粗判，再检查保守遮挡结论。
     * 玩家乘车相对坐标直接放行，未知区块也按可见处理。 */
    public boolean shouldRenderCar(Level world, long trainId, int carIndex, Vec3 center,
                                          float yaw, float pitch, boolean relative) {
        return shouldRenderCar(world, trainId, carIndex, center, yaw, pitch, relative, 0, null, null,
                new LegacyTrainQueryKey(trainId, carIndex));
    }

    /** 从可选提供者取得车厢边界，并传入实际车厢间距。
     * 提供者缺席时以默认车体尺寸作遮挡判断。 */
    public boolean shouldRenderCar(Level world, mtr.data.TrainClient train, int carIndex,
                                          Vec3 center, float yaw, float pitch, boolean relative) {
        CarBoundsProvider provider = carBoundsProvider;
        AABB knownBounds = provider == null || relative || world == null || train == null || !finite(center)
                ? null : provider.bounds(train, carIndex, center, yaw, pitch);
        return shouldRenderCar(world, train == null ? 0L : train.id, carIndex, center, yaw, pitch, relative,
                train == null ? 0 : train.spacing, train, knownBounds,
                train == null ? new LegacyTrainQueryKey(0L, carIndex)
                        : new TrainQueryKey(train, train.id, carIndex));
    }

    /** 统一处理两种调用入口，先构造可用于粗判的边界。
     * 乘车相对坐标放行；未知区块仍应用距离和视锥筛选。 */
    @SuppressWarnings("deprecation")
    private boolean shouldRenderCar(Level world, long trainId, int carIndex, Vec3 center,
                                           float yaw, float pitch, boolean relative, int spacing,
                                           mtr.data.TrainClient train, AABB knownBounds,
                                           Object queryKey) {
        Object stateKey = queryKey;
        if (relative || world == null || !finite(center)) {
            OCCLUSION_CONFIRMATIONS.remove(stateKey);
            return true;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        BlockPos carPos = new BlockPos(
                (int) Math.floor(center.x),
                (int) Math.floor(center.y),
                (int) Math.floor(center.z));
        AABB bounds = validBounds(knownBounds, center) ? knownBounds
                : defaultCarBounds(center, yaw, pitch,
                train == null ? Math.max(1D, spacing) : TrainLengthCache.length(train, carIndex, spacing));
        if (bounds == null) {
            OCCLUSION_CONFIRMATIONS.remove(stateKey);
            return true;
        }
        if (!world.hasChunkAt(carPos)) {
            OCCLUSION_CONFIRMATIONS.remove(stateKey);
            // Chunk availability is not visibility evidence. Keep the train
            // visible until the client has enough data to prove occlusion.
            return true;
        }
        Result gate = getRenderResult(carPos, world, bounds, view,
                box -> checkCarBlocked(world, stateKey, carIndex, center, yaw, pitch, queryKey, box));
        if (gate == Result.UNLOADED) {
            OCCLUSION_CONFIRMATIONS.remove(stateKey);
            return true;
        }
        return isRenderable(gate);
    }

    private Result checkCarBlocked(Level world, Object trainKey, int carIndex, Vec3 center,
                                    float yaw, float pitch, Object queryKey, AABB bounds) {
        if (!hasChunksLoaded(world, bounds)) {
            OCCLUSION_CONFIRMATIONS.remove(trainKey);
            return Result.PENDING;
        }
        return occlusionResult(world, trainKey, carIndex, center, yaw, pitch, queryKey, bounds);
    }

    /** 校验提供者边界包含车厢原点且坐标有限。
     * 无效边界退回默认范围，避免异常值进入调度器。 */
    private boolean validBounds(AABB bounds, Vec3 center) {
        return bounds != null && finite(center)
                && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
                && bounds.contains(center);
    }

    /** 从当前车厢长度、横向 4、高度至少 3 的采样范围构造世界边界。 */
    private AABB defaultCarBounds(Vec3 center, float yaw, float pitch, double carLength) {
        if (!finite(center) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        double halfLength = Math.max(0.5D, carLength * 0.5D);
        double sinPitch = Math.abs(Math.sin(pitch));
        double cosPitch = Math.abs(Math.cos(pitch));
        double longitudinal = halfLength * cosPitch + DEFAULT_HEIGHT * sinPitch;
        double halfX = longitudinal * Math.abs(Math.cos(yaw))
                + DEFAULT_HALF_WIDTH * Math.abs(Math.sin(yaw)) + DEFAULT_BOUNDS_PADDING;
        double halfZ = longitudinal * Math.abs(Math.sin(yaw))
                + DEFAULT_HALF_WIDTH * Math.abs(Math.cos(yaw)) + DEFAULT_BOUNDS_PADDING;
        double halfY = halfLength * sinPitch + DEFAULT_HEIGHT * 0.5D * cosPitch
                + DEFAULT_BOUNDS_PADDING;
        double middleY = center.y + DEFAULT_HEIGHT * 0.5D * Math.cos(pitch);
        return new AABB(center.x - halfX, middleY - halfY, center.z - halfZ,
                center.x + halfX, middleY + halfY, center.z + halfZ);
    }

    /** 检查世界坐标是否可用于边界与方块位置计算。
     * 非有限值保持渲染，避免错误遮挡证明。 */
    private boolean finite(Vec3 position) {
        return position != null && Double.isFinite(position.x)
                && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    /** 兼容旧调用入口，统一执行距离、视锥与遮挡判断。 */
    public boolean shouldCullOccludedCar(Level world, String trainId, int carIndex, Vec3 center,
                                         float yaw, float pitch) {
        if (world == null || trainId == null || !finite(center)) return false;
        AABB bounds = defaultCarBounds(center, yaw, pitch, 1D);
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        return !isRenderable(getRenderResult(world, bounds, getRenderDistance(), view,
                box -> checkCarBlocked(world, trainId + ":" + carIndex, carIndex, center, yaw, pitch,
                        trainId + ":" + carIndex, box)));
    }

    /** 对当前车厢边界执行遮挡查询与连续确认。
     * 任何不可证明的状态都重置确认并继续渲染。 */
    private Result occlusionResult(Level world, Object trainKey, int carIndex,
                                                 Vec3 center, float yaw, float pitch,
                                                 Object queryKey, AABB bounds) {
        if (world == null || trainKey == null || !finite(center)) {
            return Result.ALLOWED;
        }
        if (confirmationWorld != world || OCCLUSION_CONFIRMATIONS.size() > 4096) {
            OCCLUSION_CONFIRMATIONS.clear();
            confirmationWorld = world;
        }
        Object key = trainKey;
        OcclusionScheduler.QueryResult result = OcclusionScheduler.query(world, queryKey, bounds, null);
        if (result.type() == OcclusionScheduler.ResultType.UNLOADED) {
            OCCLUSION_CONFIRMATIONS.remove(key);
            return Result.PENDING;
        }
        if (result.type() != OcclusionScheduler.ResultType.OCCLUDED) {
            // VISIBLE and PENDING are deliberately render-admitting states.
            OCCLUSION_CONFIRMATIONS.remove(key);
            return result.type() == OcclusionScheduler.ResultType.PENDING
                    ? Result.PENDING : Result.VISIBLE;
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        if (!OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(
                world, view.cameraPosition(), bounds, result.witness())) {
            OCCLUSION_CONFIRMATIONS.remove(key);
            return Result.VISIBLE;
        }
        long frame = FrustumCache.frameId();
        OcclusionConfirmation previous = OCCLUSION_CONFIRMATIONS.get(key);
        boolean stable = previous != null
                && previous.camera.distanceToSqr(view.cameraPosition()) < 0.25D * 0.25D
                && previous.center.distanceToSqr(center) < 0.25D * 0.25D
                && Math.abs(previous.yaw - yaw) < 0.02F
                && Math.abs(previous.pitch - pitch) < 0.02F
                && angleDifferenceDegrees(previous.cameraYaw, view.yaw()) < 1.0F
                && angleDifferenceDegrees(previous.cameraPitch, view.pitch()) < 1.0F;
        int streak = stable && previous.frame == frame ? previous.streak
                : stable && previous.frame == frame - 1 ? previous.streak + 1 : 1;
        OCCLUSION_CONFIRMATIONS.put(key, new OcclusionConfirmation(center, view.cameraPosition(),
                yaw, pitch, view.yaw(), view.pitch(), frame, streak));
        return streak >= 3 ? Result.OCCLUDED : Result.VISIBLE;
    }

    /** 保存上一确认帧的相机与车厢姿态。
     * 相邻帧位置或朝向变化过大时，连续确认从头开始。 */
    private record OcclusionConfirmation(Vec3 center, Vec3 camera, float yaw, float pitch,
                                         float cameraYaw, float cameraPitch, long frame, int streak) {}

    /** Separates TrainClient instances by reference identity without retaining them strongly. */
    private static final class TrainQueryKey {
        private final WeakReference<mtr.data.TrainClient> train;
        private final long trainId;
        private final int carIndex;
        private final int identityHash;

        private TrainQueryKey(mtr.data.TrainClient train, long trainId, int carIndex) {
            this.train = new WeakReference<>(train);
            this.trainId = trainId;
            this.carIndex = carIndex;
            this.identityHash = train == null ? 0 : System.identityHashCode(train);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TrainQueryKey key) || trainId != key.trainId || carIndex != key.carIndex) {
                return false;
            }
            mtr.data.TrainClient first = train.get();
            mtr.data.TrainClient second = key.train.get();
            return first != null && first == second;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Long.hashCode(trainId) + carIndex)
                    + identityHash;
        }
    }

    /** Legacy callers use an isolated value key and cannot collide with TrainClient keys. */
    private record LegacyTrainQueryKey(long trainId, int carIndex) {}

    /** 计算跨越 0/360 度的相机朝向差。
     * 用于决定相邻帧能否沿用连续确认次数。 */
    private float angleDifferenceDegrees(float first, float second) {
        float difference = Math.abs((first - second) % 360.0F);
        return Math.min(difference, 360.0F - difference);
    }


    /** 对整列车的轨迹位置做保守距离与视锥粗判。
     * 输入不完整或靠近相机时返回可渲染。 */
    public boolean isWholeTrainBeyondDistance(Level world, Vec3[] positions, int spacing) {
        if (world == null || positions == null || positions.length < 2 || Minecraft.getInstance().level != world) {
            return false;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 position : positions) {
            if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
                return false;
            }
            minX = Math.min(minX, position.x);
            minY = Math.min(minY, position.y);
            minZ = Math.min(minZ, position.z);
            maxX = Math.max(maxX, position.x);
            maxY = Math.max(maxY, position.y);
            maxZ = Math.max(maxZ, position.z);
        }
        double margin = Math.max(BOUNDING_RADIUS, spacing);
        double renderDistance = IVRConfig.trainRD;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        AABB bounds = new AABB(minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin);
        Result result = getRenderResult(world, bounds, renderDistance, view, box -> Result.VISIBLE);
        return result == Result.DISTANCE || result == Result.FRUSTUM;
    }

}
