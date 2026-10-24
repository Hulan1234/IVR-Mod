package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.texture.TrainTexturePreloader;
import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端画面开始绘制时推进可见性帧编号。
 * 供本帧的分段遮挡缓存识别何时应当失效。
 */
@Mixin(GameRenderer.class)
public abstract class VisibilityFrameMixin {
    /**
     * 每次 GameRenderer.render 入口调用一次帧标记。
     * 本方法只更新编号，不直接执行可见性查询。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void ivr$beginVisibilityFrame(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        FrustumCache.beginFrame();
        RenderDebugStats.beginFrame();
        RenderCacheLifecycle.beginFrame(Minecraft.getInstance().level);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ivr$drainTrainTextures(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        TrainTexturePreloader.drain();
        EyeCandyPreloader.drain();
        RenderCacheLifecycle.drain();
        RenderDebugStats.endFrame();
    }
}
