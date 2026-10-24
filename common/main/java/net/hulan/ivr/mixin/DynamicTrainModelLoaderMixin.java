package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.texture.TrainTexturePreloader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {
        "forge.cn.zbx1425.mtrsteamloco.render.integration.DynamicTrainModelLoader",
        "fabric.cn.zbx1425.mtrsteamloco.render.integration.DynamicTrainModelLoader"},
        remap = false)
public abstract class DynamicTrainModelLoaderMixin {
    @Inject(method = "resolveTexture", at = @At("RETURN"), remap = false)
    private static void ivr$warmResolvedTexture(String textureId,
                                                 java.util.function.Function<String, String> resolver,
                                                 CallbackInfoReturnable<ResourceLocation> cir) {
        TrainTexturePreloader.requestAnteTexture(cir.getReturnValue());
    }
}
