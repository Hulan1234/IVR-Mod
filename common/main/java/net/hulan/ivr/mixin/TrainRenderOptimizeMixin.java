package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.utils.TrainRenderOptimize;
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
 * 规则：
 *   - 距离 ≤ TRAIN_RENDER_DISTANCE（150 格）：完整渲染（MTR 原模型，含内饰）；
 *   - 距离 > 150 格：完全不渲染。
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
     * 150 格内保持完整渲染，只有超出距离或整节车厢完全被遮挡时才跳过。
     */
    @Inject(method = "renderCar", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderCar(int index, double x, double y, double z, float yaw, float pitch, boolean backIsFront, boolean isLastCar, CallbackInfo ci) {
        // 玩家坐在列车上时坐标已是相对相机，无需再减相机位置
        boolean relative = this.train.getViewOffset() != null; // 判断 MTR 传入的列车坐标是否已经是相机相对坐标。
        // 判断列车坐标是否已经是相机相对坐标。
        Vec3 rel = TrainRenderOptimize.toCameraRelative(x, y, z, relative); // 将车厢位置统一转换为相机相对坐标。
        if (rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel(); // 超过列车渲染距离时跳过车厢。
            return; // 距离已不满足时不再执行遮挡检测。
        }
        if (!relative && TrainRenderOptimize.shouldCullOccludedCar(
                net.minecraft.client.Minecraft.getInstance().level,
                this.train.trainId,
                index,
                new Vec3(x, y, z),
                yaw,
                pitch)) { // 只在异步结果确认整节车厢被遮挡时取消本次渲染。
            ci.cancel(); // 只隐藏异步确认完全被遮挡的整节车厢。
        }
    }

    /**
     * 注入到 renderConnection（车厢连接件渲染）开头。
     * 与车厢使用相同的 150 格距离上限。
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

    /**
     * 辅助部件保持完整显示，直到超出列车渲染距离。
     */
    private void ivr$cullAuxiliary(Vec3[] corners, CallbackInfo ci) {
        boolean relative = this.train.getViewOffset() != null; // 判断辅助部件坐标是否为相对坐标。
        Vec3 firstCorner = corners == null || corners.length == 0 ? null : corners[0]; // 取第一个角点作为距离代表点。
        Vec3 rel = TrainRenderOptimize.toCameraRelative(firstCorner, relative); // 将辅助部件位置转换到相机坐标系。
        if (rel != null && rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel(); // 超过 150 格时跳过辅助部件渲染。
        }
    }
}
