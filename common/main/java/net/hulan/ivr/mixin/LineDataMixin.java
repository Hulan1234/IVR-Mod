package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {
        "top.mcmtr.data.CatenaryData",
        "top.mcmtr.data.TransCatenaryData",
        "top.mcmtr.data.RigidCatenaryData"
}, remap = false)
public abstract class LineDataMixin {

    @Inject(method = {
            "load",
            "addCatenary", "removeCatenaryConnection", "removeCatenaryNode",
            "addTransCatenary", "removeTransCatenaryConnection", "removeTransCatenaryNode",
            "addRigidCatenary", "removeRigidCatenaryConnection", "removeRigidCatenaryNode"
    }, at = @At("TAIL"), remap = false, require = 0)
    private void ivr$invalidateCatenaryCache(@Coerce CallbackInfo ci) {
        RenderDataRevision.bump();
    }
}
