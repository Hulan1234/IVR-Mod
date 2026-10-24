package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.render.RenderTrains;
import net.hulan.ivr.render_promotion.itf.TrainRenderSkipState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 过滤 Forge 列车渲染循环中的透明通道遍历。
 * 已标记跳过的客户端列车不再提交该通道的绘制动作。
 */
@Mixin(RenderTrains.class)
public abstract class RenderTrainsLoopOptimizeForgeMixin {

    /**
     * 替换指定的 Set.forEach 调用，逐列车决定是否绘制。
     * 对没有跳过状态接口的列车仍执行原消费者。
     */
    @Redirect(method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V", ordinal = 1), remap = false)
    private static void ivr$renderVisibleTranslucentTrainPass(Set<TrainClient> trains, Consumer<TrainClient> action) {
        for (TrainClient train : trains) {
            if (!(train instanceof TrainRenderSkipState) || !((TrainRenderSkipState) train).ivr$skipDistantCars()) {
                action.accept(train);
            }
        }
    }
}
