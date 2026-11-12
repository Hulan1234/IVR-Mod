package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.BlockEntityMapper;
import net.hulan.ivr.render_promotion.bounds.MsdBlockEntityBoundsRegistry;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"top.mcmtr.render.RenderPIDS", "top.mcmtr.render.RenderCustomTextSign"}, remap = false)
public abstract class MSDDisplayBoundsMixin {

    @Final
    @Shadow(remap = false)
    private float startX;

    @Final
    @Shadow(remap = false)
    private float startY;

    @Final
    @Shadow(remap = false)
    private float startZ;

    @Final
    @Shadow(remap = false)
    private float scale;

    @Final
    @Shadow(remap = false)
    private float totalScaledWidth;

    @Final
    @Shadow(remap = false)
    private float maxHeight;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void ivr$captureDisplayBounds(BlockEntityMapper entity,
                                          float tickDelta,
                                          PoseStack matrices,
                                          MultiBufferSource vertexConsumers,
                                          int light,
                                          int overlay,
                                          CallbackInfo ci) {
        if (scale > 0) {
            double offset = (Math.abs(startX - 8) + Math.abs(startY) + Math.abs(startZ - 8)) / 16.0;
            double extent = Math.abs(totalScaledWidth / scale) + Math.abs(maxHeight / 16.0) + 10.0 / scale;
            MsdBlockEntityBoundsRegistry.capture(entity, offset + extent);
        } else {
            MsdBlockEntityBoundsRegistry.capture(entity, Double.NaN);
        }
    }
}
