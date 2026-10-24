package net.hulan.ivr.render_promotion.itf;

import net.minecraft.resources.ResourceLocation;

/** Supplies the renderer-resolved train texture without reflecting into MTR. */
public interface TrainTextureSource {

    ResourceLocation ivr$getTrainTexture();
}
