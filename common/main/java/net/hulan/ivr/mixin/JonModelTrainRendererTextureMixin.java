package net.hulan.ivr.mixin;

import mtr.render.JonModelTrainRenderer;
import net.hulan.ivr.render_promotion.itf.TrainTextureSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Uses MTR's public resolver and its actual renderCar path convention. */
@Mixin(JonModelTrainRenderer.class)
public abstract class JonModelTrainRendererTextureMixin implements TrainTextureSource {

    @Shadow(remap = false) @Final
    public String textureId;

    @Shadow(remap = false)
    public abstract ResourceLocation resolveTexture(String textureId,
                                                     java.util.function.Function<String, String> formatter);

    @Override
    public ResourceLocation ivr$getTrainTexture() {
        if (textureId == null || textureId.isEmpty()) return null;
        return resolveTexture(textureId, value -> value + ".png");
    }
}
