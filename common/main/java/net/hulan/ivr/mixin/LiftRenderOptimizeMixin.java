package net.hulan.ivr.mixin;

import mtr.data.LiftClient;
import net.hulan.ivr.render_promotion.visibility.LiftVisibility;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 在 LiftClient 绘制回调前检查电梯可见性并复用短期结果。
 * 只跳过不可见电梯的顶点生成，不取消 tickClient 的其余逻辑。
 */
@Mixin(LiftClient.class)
public abstract class LiftRenderOptimizeMixin {

    /**
     * 只拦截电梯绘制回调，保留 tickClient 其余逻辑。
     * 相对坐标跳过世界空间遮挡检查，绝对坐标进入遮挡策略。
     */
    @Redirect(method = "tickClient",
            at = @At(value = "INVOKE",
                    target = "Lmtr/data/LiftClient$RenderLift;renderLift(DDDFF)V"),
            remap = false)
    private void ivr$renderLiftIfVisible(LiftClient.RenderLift renderLift,
                                         double x,
                                         double y,
                                         double z,
                                         float frontOpen,
                                         float backOpen) {
        LiftClient lift = (LiftClient) (Object) this;
        boolean relative = lift.getViewOffset() != null;
        if (LiftVisibility.getInstance().shouldRender(net.minecraft.client.Minecraft.getInstance().level, lift.id, new Vec3(x, y, z), relative)) {
            renderLift.renderLift(x, y, z, frontOpen, backOpen);
        }
    }
}
