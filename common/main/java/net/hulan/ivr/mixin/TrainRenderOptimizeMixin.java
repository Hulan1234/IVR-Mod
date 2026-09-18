package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.utils.TrainRenderOptimize;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车渲染优化 Mixin。
 * 目的：降低列车渲染开销。
 * 规则：
 *   - 距离 ≤ TRAIN_RENDER_DISTANCE（250 格）：完整渲染（MTR 原模型，含内饰）；
 *   - 距离 > 250 格：完全不渲染。
 *   - 只有整节车厢所有采样点都被实心方块遮挡时才剔除。
 * 所有列车（含玩家乘坐的）都按此距离规则渲染。
 */
@Mixin(value = JonModelTrainRenderer.class)
public abstract class TrainRenderOptimizeMixin {

    /** 当前列车（MTR 客户端数据），用于读取视角偏移。 */
    @Shadow(remap = false)
    @Final
    private TrainClient train;

    /**
     * 注入到 renderCar（每节车厢渲染）开头。
     * 250 格内保持完整渲染，只有超出距离、视锥或整节车厢完全被遮挡时才跳过。
     */
    @Inject(method = "renderCar", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderCar(int index, double x, double y, double z, float yaw, float pitch, boolean backIsFront, boolean isLastCar, CallbackInfo ci) {
        boolean relative = this.train.getViewOffset() != null;
        if (!TrainRenderOptimize.shouldRenderTrainCar(net.minecraft.client.Minecraft.getInstance().level, this.train.id, index, new Vec3(x, y, z), yaw, pitch, relative)) {
            ci.cancel();
        }
    }

    /**
     * 注入到 renderConnection（车厢连接件渲染）开头。
     * 与车厢使用相同的 250 格距离上限。
     */
    @Inject(method = "renderConnection", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderConnection(Vec3 corner1, Vec3 corner2, Vec3 corner3, Vec3 corner4, Vec3 corner5, Vec3 corner6, Vec3 corner7, Vec3 corner8, double d1, double d2, double d3, float yaw, float pitch, CallbackInfo ci) {
        ivr$cullAuxiliary(new Vec3[]{corner1, corner2, corner3, corner4, corner5, corner6, corner7, corner8}, ci); // 使用连接件的全部角点进行距离判断。
    }

    /**
     * 注入到 renderBarrier（屏蔽门/屏障渲染）开头。
     * 与连接件同样处理。
     */
    @Inject(method = "renderBarrier", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderBarrier(Vec3 corner1, Vec3 corner2, Vec3 corner3, Vec3 corner4, Vec3 corner5, Vec3 corner6, Vec3 corner7, Vec3 corner8, double d1, double d2, double d3, float yaw, float pitch, CallbackInfo ci) {
        ivr$cullAuxiliary(new Vec3[]{corner1, corner2, corner3, corner4, corner5, corner6, corner7, corner8}, ci); // 使用屏障的全部角点进行距离判断。
    }

    /** 辅助部件保持完整显示，直到超出列车渲染距离或当前视锥。 */
    @Unique
    private void ivr$cullAuxiliary(Vec3[] corners, CallbackInfo ci) {
        if (corners == null || corners.length == 0) {
            return;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int count = 0;
        for (Vec3 corner : corners) {
            if (corner != null) {
                x += corner.x;
                y += corner.y;
                z += corner.z;
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        boolean relative = this.train.getViewOffset() != null;
        Vec3 center = new Vec3(x / count, y / count, z / count);
        Vec3 rel = TrainRenderOptimize.toCameraRelative(center, relative);
        if (rel != null && (rel.lengthSqr() > TrainRenderOptimize.getTrainRenderDistance() * TrainRenderOptimize.getTrainRenderDistance()
                || TrainRenderOptimize.isOutsideFrustum(rel, 12.0D))) {
            ci.cancel();
        }
    }
}
