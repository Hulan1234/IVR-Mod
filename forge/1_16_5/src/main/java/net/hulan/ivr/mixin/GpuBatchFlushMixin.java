package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flush while the exact CPU builder remains available for rejected GPU submissions. */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class GpuBatchFlushMixin {

    @Inject(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;)V", at = @At("HEAD"), require = 0)
    private void ivr$flushRenderType(RenderType renderType, CallbackInfo callbackInfo) {
        GpuInstanceBatch.markTypedFlushHookAvailable();
        GpuInstanceBatch.flush(renderType);
    }
}
