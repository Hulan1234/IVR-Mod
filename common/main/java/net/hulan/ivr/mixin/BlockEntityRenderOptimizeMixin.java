package net.hulan.ivr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.BlockEntityMapper;
import net.hulan.ivr.util.TrainRenderOptimize;
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

    private static final double BLOCK_ENTITY_BOUNDING_RADIUS = 16.0D;

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
            final net.minecraft.world.phys.Vec3 rel = TrainRenderOptimize.toCameraRelative(pos);
            if (rel == null) {
                return;
            }
            // 距离剔除保留原有阈值；视锥剔除只接受完整包围球在视线外的情况。
            final double renderDistance = TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE + BLOCK_ENTITY_BOUNDING_RADIUS;
            final double thresholdSquared = renderDistance * renderDistance;
            // A generous sphere makes this conservative: only a completely out-of-view entity is culled.
            if (rel.lengthSqr() >= thresholdSquared || TrainRenderOptimize.isOutsideFrustum(rel, BLOCK_ENTITY_BOUNDING_RADIUS)) {
                ci.cancel();
            }
        }
    }
}
