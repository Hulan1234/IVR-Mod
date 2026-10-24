package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.render_promotion.utils.TrainRenderPolicy;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

    /**
     * 在 MTR 车厢与连接件入口执行保守可见性判断。
     * 相对坐标乘车视图始终放行，只有完整遮挡证据才隐藏车厢。
     */
@Mixin(value = JonModelTrainRenderer.class)
public abstract class TrainRenderOptimizeMixin {

    /** 当前列车的客户端数据。
     * 用于读取乘车相对视角及车厢标识。 */
    @Shadow(remap = false)
    @Final
    private TrainClient train;

    /** 在车厢渲染入口选择模型阶段并检查可见性。
     * 取消绘制时同步清理阶段模式，防止影响后续车厢。 */
    @Inject(method = "renderCar", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderCar(int carIndex,
                                       double x,
                                       double y,
                                       double z,
                                       float yaw,
                                       float pitch,
                                       boolean doorLeftOpen,
                                       boolean doorRightOpen,
                                       CallbackInfo ci) {
        long started = RenderDebugStats.startTiming();
        boolean relative = this.train.getViewOffset() != null;
        TrainVisibility.getInstance().prepareRenderMode(net.minecraft.client.Minecraft.getInstance().level, this.train,
                new Vec3(x, y, z), relative);
        boolean visible = TrainVisibility.getInstance().shouldRenderCar(net.minecraft.client.Minecraft.getInstance().level,
                this.train, carIndex, new Vec3(x, y, z), yaw, pitch, relative);
        RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, visible,
                RenderDebugStats.elapsedSince(started));
        if (!visible) {
            TrainVisibility.getInstance().clearRenderMode();
            ci.cancel();
        }
    }

    /** 在正常渲染返回后恢复默认模型阶段。
     * 与取消路径共同保证状态不泄漏。 */
    @Inject(method = "renderCar", at = @At("RETURN"), remap = false)
    private void ivr$finishRenderCar(int carIndex,
                                     double x,
                                     double y,
                                     double z,
                                     float yaw,
                                     float pitch,
                                     boolean doorLeftOpen,
                                     boolean doorRightOpen,
                                     CallbackInfo ci) {
        TrainVisibility.getInstance().clearRenderMode();
    }

    /** 连接件入口收集前后两侧的完整角点。
     * 只执行宽松距离和视锥判断，不做遮挡剔除。 */
    @Inject(method = "renderConnection", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderConnection(Vec3 prevPos1,
                                              Vec3 prevPos2,
                                              Vec3 prevPos3,
                                              Vec3 prevPos4,
                                              Vec3 thisPos1,
                                              Vec3 thisPos2,
                                              Vec3 thisPos3,
                                              Vec3 thisPos4,
                                              double x,
                                              double y,
                                              double z,
                                              float yaw,
                                              float pitch,
                                              CallbackInfo ci) {
        ivr$cullAuxiliary(
                new Vec3[] {
                        prevPos1,
                        prevPos2,
                        prevPos3,
                        prevPos4,
                        thisPos1,
                        thisPos2,
                        thisPos3,
                        thisPos4},
                ci); // 使用连接件的全部角点进行距离判断。
    }

    /** 屏障入口收集八个角点以覆盖完整几何。
     * 缺失角点时辅助判断会直接放行。 */
    @Inject(method = "renderBarrier", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderBarrier(Vec3 prevPos1,
                                           Vec3 prevPos2,
                                           Vec3 prevPos3,
                                           Vec3 prevPos4,
                                           Vec3 thisPos1,
                                           Vec3 thisPos2,
                                           Vec3 thisPos3,
                                           Vec3 thisPos4,
                                           double x,
                                           double y,
                                           double z,
                                           float yaw,
                                           float pitch,
                                           CallbackInfo ci) {
        ivr$cullAuxiliary(
                new Vec3[] {
                        prevPos1,
                        prevPos2,
                        prevPos3,
                        prevPos4,
                        thisPos1,
                        thisPos2,
                        thisPos3,
                        thisPos4},
                ci); // 使用屏障的全部角点进行距离判断。
    }

    /** 由完整角点生成带余量的边界球和距离包围盒。
     * 玩家乘车视角、异常角点和世界缺失时放行。 */
    @Unique
    private void ivr$cullAuxiliary(Vec3[] corners, CallbackInfo ci) {
        long started = RenderDebugStats.startTiming();
        if (corners == null || corners.length == 0) {
            RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, true,
                    RenderDebugStats.elapsedSince(started));
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 corner : corners) {
            if (corner == null || !Double.isFinite(corner.x)
                    || !Double.isFinite(corner.y) || !Double.isFinite(corner.z)) {
                RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, true,
                        RenderDebugStats.elapsedSince(started));
                return;
            }
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        if (train.getViewOffset() != null || net.minecraft.client.Minecraft.getInstance().level == null) {
            RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, true,
                    RenderDebugStats.elapsedSince(started));
            return;
        }
        double padding = 2.0D;
        AABB bounds = new AABB(minX - padding, minY - padding, minZ - padding,
                maxX + padding, maxY + padding, maxZ + padding);
        Vec3 center = bounds.getCenter();
        FrustumCache.CameraSnapshot view = FrustumCache.capture(net.minecraft.client.Minecraft.getInstance().level);
        Vec3 rel = view.relative(center);
        double halfX = bounds.getXsize() * 0.5D;
        double halfY = bounds.getYsize() * 0.5D;
        double halfZ = bounds.getZsize() * 0.5D;
        double dx = Math.max(0.0D, Math.abs(rel.x) - halfX);
        double dy = Math.max(0.0D, Math.abs(rel.y) - halfY);
        double dz = Math.max(0.0D, Math.abs(rel.z) - halfZ);
        double renderReach = TrainRenderPolicy.getTrainRenderDistance() + 12.0D;
        if (dx * dx + dy * dy + dz * dz > renderReach * renderReach) {
            RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, false,
                    RenderDebugStats.elapsedSince(started));
            ci.cancel();
            return;
        }
        // Use the complete auxiliary AABB with a small conservative margin;
        // a box touching a frustum plane must remain renderable.
        if (view.outsideFrustum(bounds, FrustumCache.FRUSTUM_BOUNDARY_MARGIN)) {
            RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, false,
                    RenderDebugStats.elapsedSince(started));
            ci.cancel();
        } else {
            RenderDebugStats.recordOutcome(RenderDebugStats.Category.TRAIN, true,
                    RenderDebugStats.elapsedSince(started));
        }
    }
}
