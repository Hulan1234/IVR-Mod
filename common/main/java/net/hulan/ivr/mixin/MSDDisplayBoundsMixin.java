package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.bounds.MSDBoundsRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Derive conservative display bounds from the actual renderer's scale and dimensions. */
@Pseudo
@Mixin(targets = {"top.mcmtr.render.RenderPIDS", "top.mcmtr.render.RenderCustomTextSign"}, remap = false)
public abstract class MSDDisplayBoundsMixin {
    /*
     * MSD is an optional, already named dependency.  These members must not be
     * passed through the Minecraft refmap: doing so makes Mixin 0.8.7 reject
     * the mixin before target selection with "remappable @Shadow".  The
     * explicit flag is required on every Shadow; the class-level remap=false
     * does not suppress Shadow validation.
     */
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
    private void ivr$captureDisplayBounds(mtr.mappings.BlockEntityMapper entity, float delta,
            com.mojang.blaze3d.vertex.PoseStack matrices,
            net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        if (!(scale > 0)) return;
        // Rotation is around the block center. Triangle inequality contains every
        // rotated corner, including font ascenders and the one-pixel shadow.
        double offset = (Math.abs(startX - 8) + Math.abs(startY) + Math.abs(startZ - 8)) / 16.0;
        double extent = Math.abs(totalScaledWidth / scale) + Math.abs(maxHeight / 16.0) + 10.0 / scale;
        MSDBoundsRegistry.capture(entity, offset + extent);
    }
}
