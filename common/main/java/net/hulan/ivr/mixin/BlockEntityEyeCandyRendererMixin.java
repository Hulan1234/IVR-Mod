package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.hulan.ivr.render_promotion.visibility.EyeCandyVisibility;
import net.hulan.ivr.render_promotion.cache.EyeCandyMeshCache;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
            if (!visible) {
                EyeCandyMeshCache.cancel();
                ci.cancel();
                return;
            }
            if (EyeCandyMeshCache.begin(entity, matrices, vertexConsumers, light, overlay)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false, require = 0)
    private void ivr$finishEyeCandy(@Coerce Object rawEntity, float tickDelta, PoseStack matrices,
                                    MultiBufferSource vertexConsumers, int light, int overlay,
                                    CallbackInfo ci) {
        EyeCandyMeshCache.finish();
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            remap = false, require = 0)
    private VertexConsumer ivr$recordEyeCandyBuffer(MultiBufferSource source, RenderType layer) {
        return EyeCandyMeshCache.wrap(layer, source.getBuffer(layer));
    }
}
