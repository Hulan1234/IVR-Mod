package net.hulan.ivr.render_promotion.visibility;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
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

public final class TrainVisibility extends Visibility {

    private static final TrainVisibility INSTANCE = new TrainVisibility();

    public static TrainVisibility getInstance() {
        return INSTANCE;
    }

    protected AABB getBounds(BlockPos pos, AABB bounds) {
        return bounds;
    }

    protected double getRenderDistance() {
        return IVRConfig.trainRD;
    }

    private static final double BOUNDING_RADIUS = 32.0D;

    private final Map<Object, OcclusionConfirmation> OCCLUSION_CONFIRMATIONS = new HashMap<>();

    private final Object OCCLUSION_CONFIRMATION_LOCK = new Object();

    private Level confirmationWorld;

    private long confirmationGeneration;

    private volatile CarBoundsProvider carBoundsProvider;

    private final ThreadLocal<RenderMode> RENDER_MODE = ThreadLocal.withInitial(() -> RenderMode.FULL);

    public interface CarBoundsProvider {
        AABB bounds(mtr.data.TrainClient train, int carIndex, Vec3 center, float yaw, float pitch);
    }

    public void registerCarBoundsProvider(CarBoundsProvider provider) {
        carBoundsProvider = provider;
        clearOcclusionConfirmations();
    }

    public void clearOcclusionConfirmations() {
        synchronized (OCCLUSION_CONFIRMATION_LOCK) {
            OCCLUSION_CONFIRMATIONS.clear();
            confirmationWorld = null;
            confirmationGeneration++;
        }
    }

    private void removeOcclusionConfirmation(Object key) {
        synchronized (OCCLUSION_CONFIRMATION_LOCK) {
            OCCLUSION_CONFIRMATIONS.remove(key);
        }
    }

    private long ensureConfirmationWorld(Level world) {
        synchronized (OCCLUSION_CONFIRMATION_LOCK) {
            if (confirmationWorld != world) {
                OCCLUSION_CONFIRMATIONS.clear();
                confirmationWorld = world;
                confirmationGeneration++;
            }
            return confirmationGeneration;
        }
    }

    private void removeOcclusionConfirmation(Object key, Level world, long generation) {
        synchronized (OCCLUSION_CONFIRMATION_LOCK) {
            if (confirmationGeneration == generation && confirmationWorld == world) {
                OCCLUSION_CONFIRMATIONS.remove(key);
            }
        }
    }

    public void prepareRenderMode(Level world, mtr.data.TrainClient train, Vec3 center, boolean relative) {
        if (!IVRConfig.cullingEnabled) {
            RENDER_MODE.set(RenderMode.FULL);
            return;
        }
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

    public boolean shouldRenderStage(Object stage) {
        if (!IVRConfig.cullingEnabled) {
            return true;
        }
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

    public void clearRenderMode() {
        RENDER_MODE.set(RenderMode.FULL);
    }

    private enum RenderMode {
        FULL,
        EXTERIOR_ONLY,
        FAR_EXTERIOR
    }

    public boolean shouldRenderCar(Level world, long trainId, int carIndex, Vec3 center,
                                          float yaw, float pitch, boolean relative) {
        return shouldRenderCar(world, carIndex, center, yaw, pitch, relative, null, null,
                new LegacyTrainQueryKey(trainId, carIndex));
    }

    public boolean shouldRenderCar(Level world, mtr.data.TrainClient train, int carIndex,
                                          Vec3 center, float yaw, float pitch, boolean relative) {
        TrainQueryKey trainQueryKey = train == null ? null : new TrainQueryKey(train, train.id, carIndex);
        Minecraft minecraft = Minecraft.getInstance();
        if (trainQueryKey != null && world != null && world == minecraft.level && minecraft.player != null
                && train.isPlayerRiding(minecraft.player)) {
            removeOcclusionConfirmation(trainQueryKey);
            OcclusionScheduler.forgetTrainQuery(trainQueryKey);
            RENDER_MODE.set(RenderMode.FULL);
            return true;
        }
        CarBoundsProvider provider = carBoundsProvider;
        AABB knownBounds = null;
        if (provider != null && !relative && world != null && train != null && finite(center)) {
            try {
                knownBounds = provider.bounds(train, carIndex, center, yaw, pitch);
            } catch (RuntimeException invalidBounds) {
            }
        }
        if (knownBounds == null && !relative && finite(center)) {
            knownBounds = conservativeCarBounds(center);
        }
        return shouldRenderCar(world, carIndex, center, yaw, pitch, relative, train, knownBounds,
                trainQueryKey == null ? new LegacyTrainQueryKey(0L, carIndex)
                        : trainQueryKey);
    }

    @SuppressWarnings("deprecation")
    private boolean shouldRenderCar(Level world, int carIndex, Vec3 center, float yaw, float pitch,
                                    boolean relative, mtr.data.TrainClient train,
                                    AABB knownBounds, Object queryKey) {
        Object stateKey = queryKey;
        if (relative) {
            removeOcclusionConfirmation(stateKey);
            if (train != null) OcclusionScheduler.forgetTrainQuery(stateKey);
            return true;
        }
        if (world == null || !finite(center)) {
            removeOcclusionConfirmation(stateKey);
            if (train != null) OcclusionScheduler.forgetTrainQuery(stateKey);
            return false;
        }
        if (!validBounds(knownBounds, center)) {
            removeOcclusionConfirmation(stateKey);
            return false;
        }
        AABB bounds = knownBounds;
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        BlockPos carPos = new BlockPos(
                (int) Math.floor(center.x),
                (int) Math.floor(center.y),
                (int) Math.floor(center.z));
        if (!world.hasChunkAt(carPos)) {
            removeOcclusionConfirmation(stateKey);
            return false;
        }
        Result gate = getRenderResult(carPos, world, bounds, view,
                box -> checkCarBlocked(world, stateKey, carIndex, center, yaw, pitch, queryKey, box));
        if (gate == Result.UNLOADED) {
            removeOcclusionConfirmation(stateKey);
            return false;
        }
        return isRenderable(gate);
    }

    private Result checkCarBlocked(Level world, Object trainKey, int carIndex, Vec3 center,
                                    float yaw, float pitch, Object queryKey, AABB bounds) {
        if (!hasChunksLoaded(world, bounds)) {
            removeOcclusionConfirmation(trainKey);
            return Result.PENDING;
        }
        return occlusionResult(world, trainKey, carIndex, center, yaw, pitch, queryKey, bounds);
    }

    private boolean validBounds(AABB bounds, Vec3 center) {
        return bounds != null && finite(center)
                && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
                && bounds.contains(center);
    }

    private boolean finite(Vec3 position) {
        return position != null && Double.isFinite(position.x)
                && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    private AABB conservativeCarBounds(Vec3 center) {
        double radius = BOUNDING_RADIUS;
        return new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
    }

    public boolean shouldCullOccludedCar(Level world, String trainId, int carIndex, Vec3 center,
                                         float yaw, float pitch) {
        return false;
    }

    private Result occlusionResult(Level world, Object trainKey, int carIndex,
                                                 Vec3 center, float yaw, float pitch,
                                                 Object queryKey, AABB bounds) {
        if (world == null || trainKey == null || !finite(center)) {
            return Result.ALLOWED;
        }
        long generation = ensureConfirmationWorld(world);
        Object key = trainKey;
        OcclusionScheduler.QueryResult result = OcclusionScheduler.queryTrain(world, queryKey, bounds, null);
        if (result.type() == OcclusionScheduler.ResultType.UNLOADED) {
            removeOcclusionConfirmation(key, world, generation);
            return Result.PENDING;
        }
        if (result.type() == OcclusionScheduler.ResultType.DEFERRED) {
            // Admission rejection is scoped to this frame. It is not an
            // occlusion proof and must never bypass the three-frame gate.
            return Result.DEFERRED;
        }
        if (result.type() != OcclusionScheduler.ResultType.OCCLUDED) {
            removeOcclusionConfirmation(key, world, generation);
            return switch (result.type()) {
                case PENDING -> Result.PENDING;
                case DEFERRED -> Result.DEFERRED;
                case VISIBLE -> Result.VISIBLE;
                case UNLOADED -> Result.PENDING;
                case OCCLUDED -> Result.OCCLUDED;
            };
        }
        FrustumCache.CameraSnapshot view = FrustumCache.capture(world);
        if (!OcclusionRaycaster.isWholeBoundsOccludedBySingleBlock(
                world, view.cameraPosition(), bounds, result.witness())) {
            removeOcclusionConfirmation(key, world, generation);
            return Result.VISIBLE;
        }
        long frame = FrustumCache.frameId();
        int streak;
        synchronized (OCCLUSION_CONFIRMATION_LOCK) {
            if (confirmationGeneration != generation || confirmationWorld != world) {
                return Result.VISIBLE;
            }
            OcclusionConfirmation previous = OCCLUSION_CONFIRMATIONS.get(key);
            boolean stable = previous != null
                    && previous.camera.distanceToSqr(view.cameraPosition()) < 0.25D * 0.25D
                    && previous.center.distanceToSqr(center) < 0.25D * 0.25D
                    && Math.abs(previous.yaw - yaw) < 0.02F
                    && Math.abs(previous.pitch - pitch) < 0.02F
                    && angleDifferenceDegrees(previous.cameraYaw, view.yaw()) < 1.0F
                    && angleDifferenceDegrees(previous.cameraPitch, view.pitch()) < 1.0F;
            streak = stable && previous.frame == frame ? previous.streak
                    : stable && previous.frame == frame - 1 ? previous.streak + 1 : 1;
            if (previous == null && OCCLUSION_CONFIRMATIONS.size() >= 4096) {
                pruneCollectedTrainKeys();
                if (OCCLUSION_CONFIRMATIONS.size() >= 4096) {
                    OCCLUSION_CONFIRMATIONS.clear();
                    confirmationWorld = world;
                }
                streak = 1;
            }
            OCCLUSION_CONFIRMATIONS.put(key, new OcclusionConfirmation(center, view.cameraPosition(),
                    yaw, pitch, view.yaw(), view.pitch(), frame, streak));
        }
        return streak >= 3 ? Result.OCCLUDED : Result.VISIBLE;
    }

    private void pruneCollectedTrainKeys() {
        OCCLUSION_CONFIRMATIONS.keySet().removeIf(key -> key instanceof TrainQueryKey queryKey
                && queryKey.isCollected());
    }







    private float angleDifferenceDegrees(float first, float second) {
        float difference = Math.abs((first - second) % 360.0F);
        return Math.min(difference, 360.0F - difference);
    }


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
    private record LegacyTrainQueryKey(long trainId, int carIndex) {}
    private record OcclusionConfirmation(Vec3 center, Vec3 camera, float yaw, float pitch,
                                         float cameraYaw, float cameraPitch, long frame, int streak) {}
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
        public boolean equals(Object other) {
            if (!(other instanceof TrainQueryKey key) || trainId != key.trainId || carIndex != key.carIndex) {
                return false;
            }
            mtr.data.TrainClient first = train.get();
            mtr.data.TrainClient second = key.train.get();
            return first != null && first == second;
        }
        public int hashCode() {
            return 31 * (31 * Long.hashCode(trainId) + carIndex)
                    + identityHash;
        }

        private boolean isCollected() {
            return train.get() == null;
        }
    }
}
