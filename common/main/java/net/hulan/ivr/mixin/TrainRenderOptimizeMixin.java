package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.util.TrainRenderOptimize;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车渲染优化 Mixin。
 *
 * 目的：降低列车渲染开销。
 * 规则（纯距离阈值，无任何视觉剔除）：
 *   - 距离 ≤ TRAIN_RENDER_DISTANCE（200 格）：完整渲染（MTR 原模型，含内饰）；
 *   - 距离 > 200 格：完全不渲染。
 * 不做视锥剔除、遮挡剔除、身后剔除、半透明批次削减——避免误剔导致列车消失。
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
     * 只做纯距离判断：> 200 格不渲染，否则完整渲染。
     */
    @Inject(method = "renderCar", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderCar(int index, double x, double y, double z, float yaw, float pitch, boolean backIsFront, boolean isLastCar, CallbackInfo ci) {
        // 玩家坐在列车上时坐标已是相对相机，无需再减相机位置
        boolean relative = this.train.getViewOffset() != null;
        Vec3 rel = TrainRenderOptimize.toCameraRelative(x, y, z, relative);
        // 纯距离剔除：距离 > 200 格 → 不渲染；否则完整渲染
        if (rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel();
        }
    }

    /**
     * 注入到 renderConnection（车厢连接件渲染）开头。
     * 与车厢同样做纯距离剔除。
     */
    @Inject(method = "renderConnection", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderConnection(Vec3 corner1, Vec3 corner2, Vec3 corner3, Vec3 corner4, Vec3 corner5, Vec3 corner6, Vec3 corner7, Vec3 corner8, double d1, double d2, double d3, float yaw, float pitch, CallbackInfo ci) {
        ivr$cullAuxiliary(corner1, ci);
    }

    /**
     * 注入到 renderBarrier（屏蔽门/屏障渲染）开头。
     * 与连接件同样处理。
     */
    @Inject(method = "renderBarrier", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderBarrier(Vec3 corner1, Vec3 corner2, Vec3 corner3, Vec3 corner4, Vec3 corner5, Vec3 corner6, Vec3 corner7, Vec3 corner8, double d1, double d2, double d3, float yaw, float pitch, CallbackInfo ci) {
        ivr$cullAuxiliary(corner1, ci);
    }

    /**
     * 辅助部件的通用剔除逻辑：纯距离判断，> 200 格不渲染。
     * corner 取部件的第一个角点作为代表位置。
     */
    private void ivr$cullAuxiliary(Vec3 corner, CallbackInfo ci) {
        boolean relative = this.train.getViewOffset() != null;
        Vec3 rel = TrainRenderOptimize.toCameraRelative(corner, relative);
        if (rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel();
        }
    }
}
