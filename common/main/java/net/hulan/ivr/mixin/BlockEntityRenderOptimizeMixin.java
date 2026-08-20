package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.BlockEntityMapper;
import net.hulan.ivr.util.TrainRenderOptimize;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块实体渲染优化 Mixin。
 *
 * 目的：降低城市密集区（站台 / PSD / PID / 站牌 / 时钟 / 站名牌等）的渲染开销。
 *
 * 规则（纯距离阈值，无任何视觉剔除）：
 *   - 距离 < BLOCK_ENTITY_RENDER_DISTANCE（50 格）：完全渲染；
 *   - 距离 ≥ 50 格：完全不渲染。
 * 不做视锥剔除、身后剔除——避免方块实体在屏幕边缘/身后误消失。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderOptimizeMixin {

    /**
     * 注入到 BlockEntityRenderDispatcher.render 方法开头。
     *
     * @param blockEntity       当前要渲染的块实体
     * @param tickDelta         渲染插值（游戏 tick 与渲染帧之间的小数偏移）
     * @param matrices          当前矩阵栈
     * @param multiBufferSource 顶点缓冲源
     * @param ci                回调，可调用 cancel() 取消原方法执行
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ivr$cullBlockEntity(BlockEntity blockEntity, float tickDelta, PoseStack matrices, MultiBufferSource multiBufferSource, CallbackInfo ci) {
        // 只优化 MTR 生态的块实体，不影响原版方块实体
        if (blockEntity instanceof BlockEntityMapper) {
            final BlockPos pos = blockEntity.getBlockPos();
            // 方块中心相对相机的坐标
            final Vec3 rel = TrainRenderOptimize.toCameraRelative(pos);
            if (rel == null) {
                return;
            }
            // 纯距离剔除：距离 ≥ 50 格不渲染，< 50 格完全渲染
            final double thresholdSquared = TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE * TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE;
            if (rel.lengthSqr() >= thresholdSquared) {
                ci.cancel();
            }
        }
    }
}
