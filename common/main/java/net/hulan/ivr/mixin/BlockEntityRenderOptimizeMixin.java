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

/**
 * 在通用渲染调度器入口分类方块实体可见性。
 * 仅明确位于视域外或被遮挡的实体取消绘制。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderOptimizeMixin {

    /**
     * 未注册精确边界的方块实体所用的近似模型半径。
     * 实际策略仍保守处理缺少精确边界的遮挡结果。
     */
    @Unique
    private static final double BLOCK_ENTITY_BOUNDING_RADIUS = 8.0D; // 覆盖站牌等可能跨出方块的方块实体模型。

    /**
     * 在通用调度器入口判断可见性。
     * 未知结果继续进入原渲染器。
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ivr$cullAnyBlockEntity(BlockEntity blockEntity, float tickDelta, PoseStack matrices, MultiBufferSource multiBufferSource, CallbackInfo ci) {
        long started = RenderDebugStats.startTiming();
        Visibility.Result result = BlockEntityVisibility.getInstance().getRenderResult(blockEntity, BLOCK_ENTITY_BOUNDING_RADIUS);
        RenderDebugStats.record(RenderDebugStats.Category.BE, result, RenderDebugStats.elapsedSince(started));
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
