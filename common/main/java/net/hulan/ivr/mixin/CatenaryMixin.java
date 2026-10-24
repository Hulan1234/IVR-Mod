package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.visibility.SegmentVisibility;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "top.mcmtr.data.Catenary", remap = false)
public abstract class CatenaryMixin {

    @Redirect(method = "renderSegment",
            at = @At(value = "INVOKE", target = "Ltop/mcmtr/data/Catenary$RenderCatenary;renderCatenary(DDDDDDDDDDDD)V"),
            remap = false)
    private void ivr$renderVisibleCatenary(top.mcmtr.data.Catenary.RenderCatenary renderer,
            double x1, double y1, double z1, double x2, double y2, double z2,
            double count, double index, double width, double sinX, double sinZ, double increment) {
        long started = RenderDebugStats.startTiming();
        boolean visible = SegmentVisibility.getInstance().shouldRenderCatenaryLine(x1, y1, z1, x2, y2, z2);
        RenderDebugStats.recordOutcome(RenderDebugStats.Category.CATENARY, visible,
                RenderDebugStats.elapsedSince(started));
        if (visible) {
            renderer.renderCatenary(x1, y1, z1, x2, y2, z2, count, index, width, sinX, sinZ, increment);
        }
    }
}
