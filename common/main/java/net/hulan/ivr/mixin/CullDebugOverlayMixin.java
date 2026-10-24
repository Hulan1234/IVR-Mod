package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Adds optional per-render-category diagnostics to the vanilla F3 overlay. */
@Mixin(DebugScreenOverlay.class)
public abstract class CullDebugOverlayMixin {

    @Inject(method = "getGameInformation", at = @At("RETURN"), cancellable = true)
    private void ivr$addRenderOptimizationStats(CallbackInfoReturnable<List<String>> ci) {
        RenderDebugStats.requestOverlay();
        List<String> lines = new ArrayList<>(ci.getReturnValue());
        lines.addAll(java.util.Arrays.asList(RenderDebugStats.getDebugLines()));
        ci.setReturnValue(lines);
    }
}
