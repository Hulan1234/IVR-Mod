package net.hulan.ivr.mixin;

import mtr.client.ClientCache;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publish after both MTR and KSD have finished their additional cache synchronization. */
@Mixin(ClientCache.class)
public class ClientCacheMixin {

    @Inject(method = "syncAdditional", at = @At("RETURN"), remap = false)
    private void ivr$publishRenderDataRevision(CallbackInfo ci) {
        RenderDataRevision.bump();
    }
}
