package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the native renderer's chosen frustum before block-entity rendering. */
@Mixin(LevelRenderer.class)
public abstract class NativeVisibilityFrustumMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ivr$beginNativeView(CallbackInfo ci) {
        FrustumCache.clearNativeFrustum();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ivr$endNativeView(CallbackInfo ci) {
        FrustumCache.clearNativeFrustum();
    }

    @Inject(method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZIZ)V", at = @At("HEAD"), require = 0)
    private void ivr$capture(Camera camera, Frustum frustum, boolean captured, int frame, boolean spectator, CallbackInfo ci) {
        if (captured) {
            FrustumCache.clearNativeFrustum();
            return;
        }
        FrustumCache.captureNativeFrustum(Minecraft.getInstance().level, camera, frustum);
    }

    @Inject(method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V", at = @At("HEAD"), require = 0)
    private void ivr$captureNewer(Camera camera, Frustum frustum, boolean captured, boolean spectator, CallbackInfo ci) {
        if (captured) {
            FrustumCache.clearNativeFrustum();
            return;
        }
        FrustumCache.captureNativeFrustum(Minecraft.getInstance().level, camera, frustum);
    }
}
