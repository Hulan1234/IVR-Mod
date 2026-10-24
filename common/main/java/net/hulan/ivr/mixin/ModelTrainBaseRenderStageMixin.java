package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.model.ModelTrainBase;
import net.hulan.ivr.render_promotion.cache.TrainGeometryCache;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 将列车模型各渲染阶段交给当前列车可见性模式筛选。
 * 仅重定向阶段绘制调用，不改变模型参数或阶段定义。
 */
@Mixin(ModelTrainBase.class)
public abstract class ModelTrainBaseRenderStageMixin {

    /**
     * 指向 MTR 模型原有的阶段绘制方法。
     * 重定向回调通过此入口执行获准的阶段。
     */
    @Shadow(remap = false)
    protected abstract void render(PoseStack matrices, VertexConsumer consumer,
                                          ModelTrainBase.RenderStage stage, int light,
                                          float leftDoorX, float rightDoorX, float leftDoorZ, float rightDoorZ,
                                          int carIndex, int trainCars, boolean reversed, boolean isOnRoute);

    /**
     * 按当前列车渲染模式决定是否提交模型阶段。
     * 判断来自列车渲染上下文，允许时保留原调用参数。
     */
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lmtr/data/NameColorDataBase;Lnet/minecraft/resources/ResourceLocation;IFFZIIZZZZZ)V",
            at = @At(value = "INVOKE", target = "Lmtr/model/ModelTrainBase;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lmtr/model/ModelTrainBase$RenderStage;IFFFFIIZZ)V"),
            remap = false, require = 0)
    private void ivr$renderSelectedStage(ModelTrainBase model, PoseStack matrices, VertexConsumer consumer,
                                         ModelTrainBase.RenderStage stage, int light,
                                         float leftDoorX, float rightDoorX, float leftDoorZ, float rightDoorZ,
                                         int carIndex, int trainCars, boolean reversed, boolean isOnRoute) {
        if (TrainVisibility.getInstance().shouldRenderStage(stage)) {
            TrainGeometryCache.renderStage(model, matrices, consumer, stage, light, carIndex, trainCars,
                    reversed, isOnRoute, leftDoorX, rightDoorX, leftDoorZ, rightDoorZ,
                    (captureMatrices, captureConsumer) -> render(captureMatrices, captureConsumer, stage, light,
                            leftDoorX, rightDoorX, leftDoorZ, rightDoorZ, carIndex, trainCars, reversed, isOnRoute));
        }
    }
}
