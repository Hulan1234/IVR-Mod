package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车模拟入口兼容 Mixin。
 *
 * 目的：保留 MTR 的整列模拟，让车厢级渲染优化能够获得每节车厢的实时坐标。
 *
 * 当前只保留注入点，不在整列模拟入口取消列车；具体渲染剔除由 renderCar 按车厢执行。
 */
@Mixin(TrainClient.class)
public abstract class TrainSimulateOptimizeMixin {

    /**
     * 注入到 TrainClient.simulateTrain 开头。
     * 保持整列模拟完整执行，避免用路径首点错误跳过整列列车。
     *
     * @param world             列车所在世界
     * @param tickDelta         渲染插值
     * @param speedCallback     速度回调
     * @param announcementCallback    到站播报回调
     * @param lightRailAnnouncementCallback 轻轨播报回调
     * @param ci                回调，可 cancel() 取消原方法执行
     */
    @Inject(method = "simulateTrain",
            at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ivr$cullTrain(Level world, float tickDelta, TrainClient.SpeedCallback speedCallback, TrainClient.AnnouncementCallback announcementCallback, TrainClient.AnnouncementCallback lightRailAnnouncementCallback, CallbackInfo ci) {
        // 不调用 cancel，让 MTR 为整列车计算车厢位置；renderCar 再逐节决定是否提交顶点。
    }
}
