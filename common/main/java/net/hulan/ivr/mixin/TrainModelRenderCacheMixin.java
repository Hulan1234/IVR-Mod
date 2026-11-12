package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.NameColorDataBase;
import mtr.model.ModelTrainBase;
import net.hulan.ivr.render_promotion.cache.TrainCpuMeshCache;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the train stage adapter independent from train visibility and bounds
 * mixins. Only static, closed-door body stages are recorded; bogies and
 * connection geometry remain on MTR's native path.
 */
@Mixin(ModelTrainBase.class)
public abstract class TrainModelRenderCacheMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lmtr/data/NameColorDataBase;Lnet/minecraft/resources/ResourceLocation;IFFZIIZZZZZ)V",
            at = @At("HEAD"), remap = false)
    private void ivr$beginTrainRender(PoseStack matrices, MultiBufferSource buffers,
                                      NameColorDataBase livery, ResourceLocation texture,
                                      int light, float leftDoorX, float rightDoorX,
                                      boolean isEnd, int carIndex, int trainCars,
                                      boolean reversed, boolean isOnRoute,
                                      boolean renderDetails, boolean renderInterior,
                                      boolean renderText, CallbackInfo ci) {
        TrainCpuMeshCache.beginRender(texture, livery);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lmtr/data/NameColorDataBase;Lnet/minecraft/resources/ResourceLocation;IFFZIIZZZZZ)V",
            at = @At("RETURN"), remap = false)
    private void ivr$endTrainRender(PoseStack matrices, MultiBufferSource buffers,
                                    NameColorDataBase livery, ResourceLocation texture,
                                    int light, float leftDoorX, float rightDoorX,
                                    boolean isEnd, int carIndex, int trainCars,
                                    boolean reversed, boolean isOnRoute,
                                    boolean renderDetails, boolean renderInterior,
                                    boolean renderText, CallbackInfo ci) {
        TrainCpuMeshCache.endRender();
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lmtr/data/NameColorDataBase;Lnet/minecraft/resources/ResourceLocation;IFFZIIZZZZZ)V",
            at = @At(value = "INVOKE",
                    target = "Lmtr/model/ModelTrainBase;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lmtr/model/ModelTrainBase$RenderStage;IFFFFIIZZ)V"),
            remap = false, require = 0)
    private void ivr$renderTrainStage(ModelTrainBase model, PoseStack matrices,
                                      VertexConsumer consumer, ModelTrainBase.RenderStage stage,
                                      int light, float leftDoorX, float rightDoorX,
                                      float leftDoorZ, float rightDoorZ,
                                      int carIndex, int trainCars, boolean reversed,
                                      boolean isOnRoute) {
        if (!TrainVisibility.getInstance().shouldRenderStage(stage)) {
            return;
        }
        TrainCpuMeshCache.renderStage(model, matrices, consumer, stage, light, 0,
                carIndex, trainCars, reversed, isOnRoute,
                leftDoorX, rightDoorX, leftDoorZ, rightDoorZ,
                (captureMatrices, captureConsumer) ->
                        render(captureMatrices, captureConsumer, stage, light,
                                leftDoorX, rightDoorX, leftDoorZ, rightDoorZ,
                                carIndex, trainCars, reversed, isOnRoute));
    }

    /** Shadow of ModelTrainBase's protected stage renderer. */
    @Shadow(remap = false)
    protected abstract void render(PoseStack matrices, VertexConsumer consumer,
                                   ModelTrainBase.RenderStage stage, int light,
                                   float leftDoorX, float rightDoorX, float leftDoorZ,
                                   float rightDoorZ, int carIndex, int trainCars,
                                   boolean reversed, boolean isOnRoute);
}
