package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.BlockEntityMapper;
import net.hulan.ivr.utils.TrainRenderOptimize;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块实体渲染优化 Mixin。
 *
 * 目的：降低城市密集区（站台 / PSD / PID / 站牌 / 时钟 / 站名牌等）的渲染开销。
 *
 * 规则：
 *   - 距离 < BLOCK_ENTITY_RENDER_DISTANCE（50 格）：完全渲染；
 *   - 距离 ≥ 50 格：完全不渲染。
 *   - 只有保守包围球完全在视锥外时才剔除；包围球与视锥有任何交集都保留。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderOptimizeMixin {

    private static final double BLOCK_ENTITY_BOUNDING_RADIUS = 16.0D; // 为方块实体视锥判断设置保守包围半径。

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
            final net.minecraft.world.phys.Vec3 rel = TrainRenderOptimize.toCameraRelative(pos); // 计算方块实体中心相对相机的位置。
            if (rel == null) {
                return;
            }
            // 距离剔除保留原有阈值；视锥剔除只接受完整包围球在视线外的情况。
            final double renderDistance = TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE + BLOCK_ENTITY_BOUNDING_RADIUS; // 将包围半径加入距离范围，避免边缘提前消失。
            final double thresholdSquared = renderDistance * renderDistance; // 使用平方距离减少开方计算。
            // 使用足够大的包围球，只剔除完全位于视线外的方块实体。
            if (rel.lengthSqr() >= thresholdSquared
                    || TrainRenderOptimize.shouldCullBlockEntity(pos, rel, BLOCK_ENTITY_BOUNDING_RADIUS)) { // 仅剔除距离过远或异步确认完全在视锥外的方块实体。
                ci.cancel(); // 取消完全不可见或超出范围的方块实体渲染。
            }
        }
    }
}
