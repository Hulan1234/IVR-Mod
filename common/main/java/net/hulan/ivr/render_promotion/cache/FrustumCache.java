package net.hulan.ivr.render_promotion.cache;

import net.hulan.ksd.utils.ClientUtilities;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * 为方块实体、列车、轨道与接触网共享相机视角快照。
 * 相机参数未变化时复用视锥平面，减少重复计算。
 */
public final class FrustumCache {
    /** Small conservative margin for floating-point errors at frustum edges. */
    public static final double FRUSTUM_BOUNDARY_MARGIN = 0.25D;
    /** Dynamic camera bob/FOV varies by renderer version; callers must opt into
     * frustum culling only when the full projection is known. */
    private static Frustum nativeFrustum;
    private static CameraSnapshot nativeView;
    private static long nativeFrame = -1;

    public static void clearNativeFrustum() {
        nativeFrustum = null;
        nativeView = null;
        nativeFrame = -1;
        lastCameraSnapshot = null;
    }

    public static void captureNativeFrustum(Level world, Camera camera, Frustum frustum) {
        clearNativeFrustum();
        if (world == null || camera == null || frustum == null) {
            return;
        }
        CameraSnapshot view = capture(world);
        if (!view.cameraPosition().equals(camera.getPosition())
                || view.yaw() != camera.getYRot()
                || view.pitch() != camera.getXRot()) {
            return;
        }
        nativeFrustum = frustum;
        nativeView = view;
        nativeFrame = frameId;
    }

    public static boolean outsideNativeFrustum(CameraSnapshot view, AABB bounds) {
        // Scope and exact camera snapshot validation prevent stale/multiple-view reuse.
        if (nativeFrustum == null || nativeFrame != frameId || nativeView != view) return false;
        // Treat an intersecting or near-edge box as visible.  The margin is
        // deliberately small so it only absorbs plane precision errors.
        return !nativeFrustum.isVisible(bounds.inflate(FRUSTUM_BOUNDARY_MARGIN));
    }

    /**
     * 无法读取 FOV 时使用的默认半视角正切值。
     * 在第一次成功读取之前作为缓存初值。
     */
    private static final double FALLBACK_FOV_TAN_HALF = Math.tan(Math.toRadians(55.0D));
    // 当前缓存的垂直半视角正切值。
    // 反射读取失败时继续沿用该值。
    private static volatile double cachedFovTanHalf = FALLBACK_FOV_TAN_HALF;
    // FOV 缓存失效的单调时钟时间。
    // 到期后下一次读取才重新访问客户端选项。
    private static volatile long fovCacheExpiresAt;
    /**
     * 最近一次相机视角快照。
     * 相机参数保持不变时直接复用。
     */
    private static CameraSnapshot lastCameraSnapshot;
    // 当前渲染帧的单调编号。
    // 分段缓存据此识别新帧并清理临时结果。
    private static long frameId;

    /**
     * 在画面渲染入口推进帧编号。
     * 由客户端渲染钩子调用，供本帧缓存比较。
     */
    public static void beginFrame() {
        frameId++;
        clearNativeFrustum();
    }

    /**
     * 返回当前渲染帧编号。
     * 读取时不修改快照或任何可见性缓存。
     */
    public static long frameId() {
        return frameId;
    }

    /**
     * 禁止实例化共享的相机上下文工具类。
     * 所有状态和判断入口都通过静态成员访问。
     */
    private FrustumCache() {
    }

    /**
     * 获取当前世界和相机参数对应的视锥快照。
     * 世界、位置、朝向、窗口比例或 FOV 变化时重建。
     */
    public static CameraSnapshot capture(Level world) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 position = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        int width = minecraft.getWindow().getWidth();
        int height = Math.max(1, minecraft.getWindow().getHeight());
        double aspect = (double) width / height;
        double tanVertical = fovTanHalf();
        CameraSnapshot previous = lastCameraSnapshot;
        if (previous != null && previous.world() == world && previous.cameraPosition().equals(position)
                && previous.yaw() == yaw && previous.pitch() == pitch && previous.aspect() == aspect
                && previous.tanVertical() == tanVertical) {
            return previous;
        }
        CameraSnapshot cameraSnapshot = new CameraSnapshot(world, position, yaw, pitch, aspect, tanVertical,
                frustumPlanes(yaw, pitch, tanVertical, aspect));
        lastCameraSnapshot = cameraSnapshot;
        return cameraSnapshot;
    }

    /**
     * 返回垂直半视角的正切值，最多每半秒反射读取一次。
     * 读取失败时沿用上次值，初始使用保守默认值。
     */
    public static double fovTanHalf() {
        long now = System.nanoTime();
        if (now < fovCacheExpiresAt) {
            return cachedFovTanHalf;
        }
        Options clientOptions = Minecraft.getInstance().options;
        double fov = ClientUtilities.getInstance().getFov(clientOptions);
        cachedFovTanHalf = Math.tan(Math.toRadians(fov * 0.5D));
        fovCacheExpiresAt = now + 500_000_000L;
        return cachedFovTanHalf;
    }

    /**
     * 按给定视角即时构造平面，测试相机相对球体。
     * 空位置直接视为视锥外，调用者需自行处理该边界。
     */
    public static boolean outsideFrustum(Vec3 relative,
                                         double radius,
                                         float yaw,
                                         float pitch,
                                         double tanVertical,
                                         double aspect) {
        if (relative == null) {
            return true;
        }
        Vec3[] planes = frustumPlanes(yaw, pitch, tanVertical, aspect);
        return outsideFrustum(relative, radius, planes);
    }

    /**
     * 使用已构造的平面判断相机相对球体。
     * 空位置视为视锥外，平面来自调用者提供的视角。
     */
    public static boolean outsideFrustum(Vec3 relative, double radius, Vec3[] planes) {
        if (relative == null) {
            return true;
        }
        return outsideFrustum(relative.x, relative.y, relative.z, radius, planes);
    }

    /**
     * 用球体中心、半径与各视锥平面逐一比较。
     * 任一平面完全排斥球体时返回视锥外。
     */
    public static boolean outsideFrustum(double x, double y, double z, double radius, Vec3[] planes) {
        for (int index = 0; index < planes.length; index++) {
            Vec3 plane = planes[index];
            if (plane.x * x + plane.y * y + plane.z * z - (index == 4 ? 0.05D : 0.0D) < -radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从朝向、垂直半视角和宽高比构建五个平面。
     * 返回左右上下与前向平面，供后续球体或盒体测试。
     */
    public static Vec3[] frustumPlanes(double yawDegrees,
                                       double pitchDegrees,
                                       double tanVertical,
                                       double aspect) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        Vec3 right = new Vec3(-cosYaw, 0.0D, -sinYaw);
        Vec3 up = right.cross(forward);
        double tanHorizontal = tanVertical * aspect;
        return new Vec3[] {
                right.add(forward.scale(tanHorizontal)).normalize(),
                forward.scale(tanHorizontal).subtract(right).normalize(),
                up.add(forward.scale(tanVertical)).normalize(),
                forward.scale(tanVertical).subtract(up).normalize(),
                forward};
    }

    /**
     * 固定一次视角的世界、相机和视锥平面数据。
     * 记录对象可复用；内部平面数组由创建流程持有。
     */
    public record CameraSnapshot(

            Level world,
            Vec3 cameraPosition,
            float yaw,
            float pitch,
            double aspect,
            double tanVertical,
            Vec3[] planes) {

        /**
         * 将世界坐标换算为相机相对坐标。
         * 结果供球体视锥测试直接使用。
         */
        public Vec3 relative(Vec3 worldPosition) {
            return worldPosition.subtract(cameraPosition);
        }

        /**
         * 用快照平面判断相机相对球体。
         * 无需再次构造视锥平面。
         */
        public boolean outsideFrustum(Vec3 relative, double radius) {
            return FrustumCache.outsideFrustum(relative, radius, planes);
        }

        /**
         * 用快照平面判断分量形式的相机相对球体。
         * 半径允许包住跨越视锥边缘的几何体。
         */
        public boolean outsideFrustum(double x, double y, double z, double radius) {
            return FrustumCache.outsideFrustum(x, y, z, radius, planes);
        }

        /**
         * 用盒体在各平面上的投影测试世界空间边界。
         * 比包围球更贴近细长轨道或方块实体的实际形状。
         */
        public boolean outsideFrustum(AABB bounds) {
            return outsideFrustum(bounds, 0.0D);
        }

        /** Equivalent to testing bounds.inflate(inflation), without allocating a new box. */
        public boolean outsideFrustum(AABB bounds, double inflation) {
            double centerX = (bounds.minX + bounds.maxX) * 0.5D - cameraPosition.x;
            double centerY = (bounds.minY + bounds.maxY) * 0.5D - cameraPosition.y;
            double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D - cameraPosition.z;
            double halfX = bounds.getXsize() * 0.5D + inflation;
            double halfY = bounds.getYsize() * 0.5D + inflation;
            double halfZ = bounds.getZsize() * 0.5D + inflation;
            for (int index = 0; index < planes.length; index++) {
                Vec3 plane = planes[index];
                double reach = Math.abs(plane.x) * halfX + Math.abs(plane.y) * halfY
                        + Math.abs(plane.z) * halfZ;
                if (plane.x * centerX + plane.y * centerY + plane.z * centerZ + reach
                        < (index == 4 ? 0.05D : 0.0D)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 返回世界点到快照相机的平方距离。
         * 可供调用者避免开平方根的距离比较。
         */
        public double distanceSquared(Vec3 worldPosition) {
            return worldPosition.distanceToSqr(cameraPosition);
        }

        /**
         * 判断世界点是否位于指定距离以内。
         * 边界距离算作可见范围之内。
         */
        public boolean withinDistance(Vec3 worldPosition, double maxDistance) {
            return distanceSquared(worldPosition) <= maxDistance * maxDistance;
        }
    }
}
