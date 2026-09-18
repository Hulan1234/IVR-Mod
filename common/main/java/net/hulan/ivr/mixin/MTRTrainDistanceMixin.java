package net.hulan.ivr.mixin;

import net.hulan.ivr.utils.TrainRenderOptimize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Ensures MTR's internal train-distance gate does not hide trains before IVR's 250-block gate. */
@Mixin(mtr.render.TrainRendererBase.class)
public abstract class MTRTrainDistanceMixin {

    @ModifyArg(
            method = "applyAverageTransform",
            at = @At(value = "INVOKE", target = "Lmtr/render/RenderTrains;shouldNotRender(Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/Direction;)Z"),
            index = 1,
            remap = false
    )
    private static int ivr$useTrainRenderDistance(int mtrDistance) {
        return Math.max(mtrDistance, (int) Math.ceil(TrainRenderOptimize.getTrainRenderDistance()));
    }
}
