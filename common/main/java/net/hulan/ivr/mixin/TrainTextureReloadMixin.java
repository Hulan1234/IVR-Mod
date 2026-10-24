package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.texture.TrainTexturePreloader;
import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.cache.TrainGeometryCache;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drops warmup de-duplication after a resource-manager reload. */
@Mixin(TextureManager.class)
public abstract class TrainTextureReloadMixin {
    @Unique private final ThreadLocal<java.util.ArrayDeque<Long>> ivr$reloadGenerations =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);
    @Inject(method = "reload", at = @At("HEAD"))
    private void ivr$invalidateTrainTextures(CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> cir) {
        TrainGeometryCache.markResourceReloaded();
        RenderDataRevision.bump();
        RenderCacheLifecycle.queueReloadStart();
        EyeCandyPreloader.invalidate();
        ivr$reloadGenerations.get().push(TrainTexturePreloader.beginReload());
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void ivr$resumeTrainTextures(CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> cir) {
        java.util.ArrayDeque<Long> generations = ivr$reloadGenerations.get();
        if (generations.isEmpty()) {
            RenderCacheLifecycle.queueReloadEnd();
            return;
        }
        long reloadGeneration = generations.pop();
        if (generations.isEmpty()) ivr$reloadGenerations.remove();
        java.util.concurrent.CompletableFuture<Void> reloadFuture = cir.getReturnValue();
        if (reloadFuture == null) {
            RenderCacheLifecycle.queueReloadEnd();
            return;
        }
        reloadFuture.whenComplete((ignored, failure) -> {
            RenderCacheLifecycle.queueReloadEnd();
            if (failure == null) net.minecraft.client.Minecraft.getInstance().execute(() ->
                    TrainTexturePreloader.finishReload(reloadGeneration));
        });
    }
}
