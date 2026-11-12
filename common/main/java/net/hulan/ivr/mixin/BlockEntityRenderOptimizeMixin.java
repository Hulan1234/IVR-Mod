package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.hulan.ivr.render_promotion.visibility.BlockEntityVisibility;
import net.hulan.ivr.render_promotion.visibility.Visibility;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderOptimizeMixin {

    @Unique
    private static final double BLOCK_ENTITY_BOUNDING_RADIUS = 8.0D; // 覆盖站牌等可能跨出方块的方块实体模型。

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ivr$cullAnyBlockEntity(BlockEntity blockEntity, float f, PoseStack arg2, MultiBufferSource arg3, CallbackInfo ci) {
        long started = RenderDebugStats.startTiming();
        Visibility.Result result = BlockEntityVisibility.getInstance().getRenderResult(blockEntity, BLOCK_ENTITY_BOUNDING_RADIUS);
        RenderDebugStats.record(RenderDebugStats.Category.BE, result, RenderDebugStats.elapsedSince(started));
        if (result == Visibility.Result.PENDING || result == Visibility.Result.DEFERRED) {
            RenderDebugStats.recordRenderAttemptedBecausePending(RenderDebugStats.Category.BE);
        }
        if (result == Visibility.Result.DISTANCE || result == Visibility.Result.FRUSTUM
                || result == Visibility.Result.UNLOADED || result == Visibility.Result.OCCLUDED) {
            // MTR advances door interpolation in its renderer; preserve it while culled.
            if (blockEntity instanceof mtr.block.BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase door) {
                door.getOpen(mtr.MTRClient.getLastFrameDuration());
            }
            ci.cancel();
        }
    }
}
