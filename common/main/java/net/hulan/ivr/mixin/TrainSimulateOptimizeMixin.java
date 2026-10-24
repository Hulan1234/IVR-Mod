package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import net.hulan.ivr.render_promotion.texture.TrainTexturePreloader;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.hulan.ivr.render_promotion.itf.TrainRenderSkipState;
import net.hulan.ivr.render_promotion.cache.TrainGeometryCache;
import net.hulan.ivr.render_promotion.cache.TrainLengthCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import java.util.Set;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 保留 MTR 的车厢计算和副作用，只跳过不可见整列的纯绘制尾段。
 * 乘客所在列车始终保留，车厢级剔除继续负责视锥和遮挡判断。
 */
@Mixin(TrainClient.class)
public abstract class TrainSimulateOptimizeMixin implements TrainRenderSkipState {

    /**
     * 标记当前整列是否可跳过绘制提交。
     * 每次模拟开始时清零，随后按实时位置重新判断。
     */
    @Unique
    private boolean ivr$skipDistantCars;

    @Shadow(remap = false) @Final
    private Set<Runnable> trainTranslucentRenders;

    // Everything after renderCar in this method is visual submission/connector geometry.
    @Inject(method = "simulateCar", at = @At(value = "INVOKE",
            target = "Lmtr/render/TrainRendererBase;renderCar(IDDDFFZZ)V", ordinal = 0),
            cancellable = true, remap = false, require = 0, allow = 1)
    private void ivr$skipHiddenVisualTail(CallbackInfo ci) {
        if (ivr$skipDistantCars) ci.cancel();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$discardHiddenTranslucent(CallbackInfo ci) {
        if (ivr$skipDistantCars) {
            trainTranslucentRenders.clear();
            ci.cancel();
        }
    }

    /**
     * 暴露本次模拟的绘制提交标记，不用于取消 calculateCar。
     */
    @Override
    public boolean ivr$skipDistantCars() {
        return ivr$skipDistantCars;
    }

    /**
     * 在每次列车模拟开始前撤销上次的跳过标记。
     * 防止旧位置的结论影响新一次模拟。
     */
    @Inject(method = "simulateTrain", at = @At("HEAD"), remap = false)
    private void ivr$resetDistantCarFlag(Level world, float tickDelta,
                                          TrainClient.SpeedCallback speedCallback,
                                          TrainClient.AnnouncementCallback announcementCallback,
                                          TrainClient.AnnouncementCallback lightRailAnnouncementCallback, CallbackInfo ci) {
        ivr$skipDistantCars = false;
    }

    /**
     * 得到实时车厢位置后判断整列是否超出可见范围。
     * 玩家乘坐的列车始终保留逐车厢计算。
     */
    @Inject(method = "handlePositions", at = @At("HEAD"), remap = false)
    private void ivr$cullDistantTrain(Level world, Vec3[] positions, float tickDelta, CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        TrainClient train = (TrainClient) (Object) this;
        ivr$skipDistantCars = (minecraft.player == null || !train.isPlayerRiding(minecraft.player))
                && TrainVisibility.getInstance().isWholeTrainBeyondDistance(world, positions, train.spacing);
        TrainGeometryCache.observeTrain(train, positions);
        TrainLengthCache.observe(train, positions);
        TrainTexturePreloader.consider(world, train, positions);
    }
}
