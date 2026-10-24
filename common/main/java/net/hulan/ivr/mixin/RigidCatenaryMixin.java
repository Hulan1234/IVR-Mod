package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.visibility.SegmentVisibility;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 按 MSD 刚性接触网实际生成的四边形进行可见性过滤。
 * 伪 Mixin 允许未安装该模组时继续加载本模组。
 */
@Pseudo
@Mixin(targets = "top.mcmtr.data.RigidCatenary", remap = false)
public abstract class RigidCatenaryMixin {

    /**
     * 根据完整边界判断接触网片段。
     * 仅可见片段调用原绘制器。
     */
    @Redirect(method = "renderSegment",
            at = @At(value = "INVOKE", target = "Ltop/mcmtr/data/RigidCatenary$RenderRigidCatenary;renderRigidCatenary(DDDDDDDDDDDDDDDDDD)V"),
            remap = false)
    private void ivr$renderVisibleSegment(top.mcmtr.data.RigidCatenary.RenderRigidCatenary renderer,
            double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4,
            double xs1, double zs1, double xs2, double zs2, double xs3, double zs3, double xs4, double zs4,
            double y1, double y2) {
        long started = RenderDebugStats.startTiming();
        boolean visible = SegmentVisibility.getInstance().shouldRenderCatenarySegment(
                x1, z1, x2, z2, x3, z3, x4, z4,
                xs1, zs1, xs2, zs2, xs3, zs3, xs4, zs4, y1, y2);
        RenderDebugStats.recordOutcome(RenderDebugStats.Category.CATENARY, visible,
                RenderDebugStats.elapsedSince(started));
        if (visible) {
            renderer.renderRigidCatenary(
                    x1, z1, x2, z2, x3, z3, x4, z4,
                    xs1, zs1, xs2, zs2, xs3, zs3, xs4, zs4, y1, y2);
        }
    }
}
