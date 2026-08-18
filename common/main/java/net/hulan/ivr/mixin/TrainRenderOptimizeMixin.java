package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.util.TrainRenderOptimize;
import net.minecraft.client.Minecraft;
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
 * 目的：降低列车渲染开销，尤其是线网上列车数量较多的场景。
 *
 * 优化点（阈值渲染 + AABB 视锥 + 遮挡剔除 + 半透明批次削减）：
 *   1. renderCar：渲染距离内（isTooFar 判定）且距离 ≤ TRAIN_RENDER_DISTANCE（300 格）的
 *      车厢完整渲染（MTR 原模型，含内饰）；>300 格直接不渲染。
 *   2. 整车厢 AABB 视锥剔除：只有整节车厢（8 个角点）全部在视锥外才不渲染——
 *      判定标准不是几何中心，而是整个车厢。车厢部分在屏幕内就渲染，避免误剔。
 *   3. 方块遮挡剔除：从相机到车厢中心 DDA 采样，被实心方块遮挡则跳过渲染。
 *   4. 半透明批次削减（减少单线程顶点生成）：MTR 每帧对每节车厢渲染两次——
 *      不透明批次（车身主体）+ 半透明批次（车窗/灯光等）。半透明批次下距离
 *      > TRANSLUCENT_RENDER_DISTANCE 的车厢直接取消，跳过第二次顶点生成。
 *   5. renderConnection / renderBarrier：车钩/屏蔽门等辅助部件做距离 + 视锥 + 遮挡剔除。
 */
@Mixin(value = JonModelTrainRenderer.class)
public abstract class TrainRenderOptimizeMixin {

    /** 当前列车（MTR 客户端数据），用于读取视角偏移。 */
    @Shadow(remap = false)
    @Final
    private TrainClient train;

    /**
     * 当前渲染批次是否为半透明批次（继承自 TrainRendererBase 的 static 字段）。
     * 用反射读取（见 TrainRenderOptimize.getTranslucentBatch），
     * 避免 @Shadow 父类字段在运行时定位失败。
     */
    private static boolean isTranslucentBatch() {
        return TrainRenderOptimize.getTranslucentBatch();
    }

    /**
     * 注入到 renderCar（每节车厢渲染）开头。
     * 在 MTR 完整模型渲染之前，先做距离 / 视锥 / 遮挡 / 批次判定，能省则省。
     */
    @Inject(method = "renderCar", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$optimizeRenderCar(int index, double x, double y, double z, float yaw, float pitch, boolean backIsFront, boolean isLastCar, CallbackInfo ci) {
        // 玩家坐在列车上时坐标已是相对相机，无需再减相机位置
        boolean relative = this.train.getViewOffset() != null;
        Vec3 rel = TrainRenderOptimize.toCameraRelative(x, y, z, relative);
        // 超出渲染距离或距离 > 300 格 → 整节车厢不渲染
        if (TrainRenderOptimize.isTooFar(rel) || rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel();
            return;
        }
        // 整车厢 AABB 视锥剔除：只有整节车厢（8 个角点）全部在视锥外才剔除
        if (TrainRenderOptimize.isCarOutsideFrustum(rel, yaw)) {
            ci.cancel();
            return;
        }
        // 方块遮挡剔除：被实心方块完全遮挡的车厢不渲染。
        // 仅在列车数量较多（> 阈值）时启用，避免低负载时白跑射线开销。
        if (TrainRenderOptimize.shouldUseOcclusion() && TrainRenderOptimize.isCarOccluded(Minecraft.getInstance().level, new Vec3(x, y, z))) {
            ci.cancel();
            return;
        }
        // 半透明批次削减：半透明批次（车窗/灯光）下，距离超过阈值 → 跳过第二次顶点生成
        if (isTranslucentBatch() && rel.length() > TrainRenderOptimize.getTranslucentRenderDistance()) {
            ci.cancel();
        }
    }

    /**
     * 注入到 renderConnection（车厢连接件渲染）开头。
     * 连接件较小，仅做距离 / 视锥 / 遮挡剔除。
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
     * 辅助部件的通用剔除逻辑：
     * 渲染距离太远或超过列车渲染距离 → 取消渲染。
     * 整部件 AABB 视锥剔除 + 方块遮挡剔除。
     * 半透明批次下同样按阈值削减。
     * corner 取部件的第一个角点作为代表位置。
     */
    private void ivr$cullAuxiliary(Vec3 corner, CallbackInfo ci) {
        boolean relative = this.train.getViewOffset() != null;
        Vec3 rel = TrainRenderOptimize.toCameraRelative(corner, relative);
        if (TrainRenderOptimize.isTooFar(rel) || rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel();
            return;
        }
        // 部件较小，用视锥（以 corner 为参考点 + 小半径）与遮挡剔除
        if (TrainRenderOptimize.isOutsideFrustum(rel, TrainRenderOptimize.CONNECTION_RADIUS)) {
            ci.cancel();
            return;
        }
        if (TrainRenderOptimize.isCarOccluded(Minecraft.getInstance().level, corner)) {
            ci.cancel();
            return;
        }
        if (isTranslucentBatch() && rel.length() > TrainRenderOptimize.getTranslucentRenderDistance()) {
            ci.cancel();
        }
    }
}
