package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.hulan.ivr.render_promotion.visibility.EyeCandyVisibility;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.entity.BlockEntity;import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies IVR visibility and warmup before ANTE's EyeCandy renderer runs. */
@Pseudo
@Mixin(targets = {
        "fabric.cn.zbx1425.mtrsteamloco.render.block.BlockEntityEyeCandyRenderer",
        "forge.cn.zbx1425.mtrsteamloco.render.block.BlockEntityEyeCandyRenderer"},
        remap = false)
public abstract class BlockEntityEyeCandyRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private void ivr$renderEyeCandy(@Coerce Object rawEntity, float tickDelta, PoseStack matrices,
                                    MultiBufferSource vertexConsumers, int light, int overlay,
                                    CallbackInfo ci) {
        if (rawEntity instanceof BlockEntity entity) {
            EyeCandyPreloader.request(entity);
            boolean visible = EyeCandyVisibility.getInstance().shouldRender(entity);
            if (!visible) ci.cancel();
        }
    }
}
