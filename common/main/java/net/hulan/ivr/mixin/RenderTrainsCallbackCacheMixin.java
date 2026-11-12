package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.render.RenderTrains;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.hulan.ivr.render_promotion.cache.CatenaryGeometryCache;
import net.hulan.ivr.render_promotion.cache.RenderCallbackGeometryCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;
import java.util.Set;

/**
 * Captures MTR's common scheduled callback dispatch.  Catenary and rail
 * callbacks use the same dispatch path, so one version-neutral hook covers
 * both without drawing from the callback itself.
 */
@Pseudo
@Mixin(targets = "mtr.render.RenderTrains", remap = false)
public abstract class RenderTrainsCallbackCacheMixin {

    @Redirect(method = "lambda$render$10",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"),
            remap = false, require = 0)
    private static void ivr$renderCached(BiConsumer<Object, Object> callback,
                                         Object matrices, Object consumer) {
        // The BiConsumer invocation is erased to (Object, Object) in every
        // MTR jar, even though the lambda method itself captures PoseStack and
        // VertexConsumer.  Keep the redirect descriptor erased and validate
        // the values before routing them through the typed cache API.
        if (!(matrices instanceof PoseStack pose) || !(consumer instanceof VertexConsumer vertex)) {
            if (callback != null) callback.accept(matrices, consumer);
            return;
        }
        if (CatenaryGeometryCache.isMsdCallback(callback)) {
            CatenaryGeometryCache.render(callback, pose, vertex);
        } else if (RenderCallbackGeometryCache.isYamanoteDisplayCallback(callback)) {
            callback.accept(pose, vertex);
        } else {
            RenderCallbackGeometryCache.render(callback, pose, vertex);
        }
    }

    /** The queue lambda knows the texture before it selects the buffer. */
    @Inject(method = "lambda$render$11", at = @At("HEAD"), remap = false, require = 0)
    private static void ivr$beginCallbackLayer(RenderTrains.QueuedRenderLayer queuedRenderLayer,
                                               MultiBufferSource vertexConsumers,
                                               PoseStack matrices,
                                               ResourceLocation key,
                                               Set<BiConsumer<PoseStack, VertexConsumer>> value,
                                               CallbackInfo ci) {
        RenderCallbackGeometryCache.beginLayer(key);
    }

    /** Capture the exact RenderType used by this queue batch for GPU grouping. */
    @Redirect(method = "lambda$render$11",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private static VertexConsumer ivr$rememberCallbackLayer(MultiBufferSource buffers, RenderType layer) {
        RenderCallbackGeometryCache.setLayer(layer);
        return buffers.getBuffer(layer);
    }

    @Inject(method = "lambda$render$11", at = @At("RETURN"), remap = false, require = 0)
    private static void ivr$endCallbackLayer(RenderTrains.QueuedRenderLayer queuedRenderLayer,
                                             MultiBufferSource vertexConsumers,
                                             PoseStack matrices,
                                             ResourceLocation key,
                                             Set<BiConsumer<PoseStack, VertexConsumer>> value,
                                             CallbackInfo ci) {
        RenderCallbackGeometryCache.endLayer();
    }
}
