package net.hulan.ivr.mixin;

import mtr.data.Rail;
import net.hulan.ivr.render_promotion.visibility.SegmentVisibility;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在 MTR 轨道细分四边形提交前检查实际几何边界。
 * 可见性判定仅跳过确定不可见的片段。
 */
@Mixin(Rail.class)
public abstract class RailRenderOptimizeMixin {

    /**
     * 对曲线生成的每个四边形执行分段可见性判断。
     * 可见时调用原渲染回调。
     */
    @Redirect(method = "renderSegment",
            at = @At(value = "INVOKE",
                    target = "Lmtr/data/Rail$RenderRail;renderRail(DDDDDDDDDD)V"),
            remap = false)
    private void ivr$renderRailQuadIfVisible(Rail.RenderRail renderRail,
                                               double x1, double z1,
                                               double x2, double z2,
                                               double x3, double z3,
                                               double x4, double z4,
                                               double y1, double y2) {
        long started = RenderDebugStats.startTiming();
        boolean visible = SegmentVisibility.getInstance().shouldRenderRailQuad(x1, z1, x2, z2, x3, z3, x4, z4, y1, y2);
        RenderDebugStats.recordOutcome(RenderDebugStats.Category.RAILBOX, visible,
                RenderDebugStats.elapsedSince(started));
        if (visible) {
            renderRail.renderRail(x1, z1, x2, z2, x3, z3, x4, z4, y1, y2);
        }
    }
}
