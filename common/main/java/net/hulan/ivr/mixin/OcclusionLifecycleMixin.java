package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.hulan.ivr.render_promotion.texture.TrainTexturePreloader;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases ray worker queues when the client drops a world or exits. */
@Mixin(Minecraft.class)
public abstract class OcclusionLifecycleMixin {

    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), require = 0)
    private void ivr$stopOcclusionOnWorldDrop(CallbackInfo ci) {
        OcclusionScheduler.shutdown();
        RenderCacheLifecycle.invalidate();
        EyeCandyPreloader.invalidate();
        TrainTexturePreloader.onWorldExit();
        TrainVisibility.getInstance().clearOcclusionConfirmations();
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void ivr$startTrainWarmupAfterPlayerJoin(CallbackInfo ci) {
        TrainTexturePreloader.onClientTick();
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void ivr$stopOcclusionOnClientClose(CallbackInfo ci) {
        OcclusionScheduler.shutdown();
        RenderCacheLifecycle.invalidate();
        EyeCandyPreloader.invalidate();
        TrainTexturePreloader.onWorldExit();
        TrainVisibility.getInstance().clearOcclusionConfirmations();
    }
}
