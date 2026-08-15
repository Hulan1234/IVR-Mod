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
 * 方块实体渲染剔除 Mixin。
 *
 * 目的：降低城市密集区（站台 / PSD / PID / 站牌 / 时钟 / 站名牌等）的 GPU 压力。
 * 原版块实体只在 chunk 级别做视锥剔除，站台内所有块实体每帧都会全量提交渲染
 * （每个都要创建闭包、拼字符串、scheduleRender 排队），导致大量 draw call。
 *
 * 做法：拦截统一的块实体渲染入口 BlockEntityRenderDispatcher.render，
 * 在其 HEAD 处对 MTR 生态的块实体（BlockEntityMapper 子类）做剔除：
 *   - 距离 > BLOCK_ENTITY_RENDER_DISTANCE（50 格）：ci.cancel() 完全不渲染；
 *   - 在玩家身后（rel·forward < 0）：ci.cancel() 不渲染。
 * 无中间档、无隔帧（隔帧会导致闪烁），简单且无视觉副作用。
 * 左右/上下视野边缘的块实体仍正常渲染（只剔除真正在正后方的）。
 *
 * 注：BlockEntityRenderDispatcher.render 是 LevelRenderer 渲染所有块实体的统一入口，
 * 在此拦截即可覆盖 MTR 原版 + IVR/KSD 的全部块实体（它们都继承 BlockEntityMapper），
 * 且不影响原版箱子 / 告示牌 / 信标等（非 BlockEntityMapper）。
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
            // 距离平方，与阈值平方比较（避免开方）
            final double distanceSquared = rel.lengthSqr();
            // 纯距离剔除：> 50 格完全不渲染，< 50 格完全渲染（无中间档、无隔帧）
            final double thresholdSquared = TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE * TrainRenderOptimize.BLOCK_ENTITY_RENDER_DISTANCE;
            // 距离太远 或 在玩家身后 → 不渲染
            if (distanceSquared > thresholdSquared || TrainRenderOptimize.isBehindCamera(rel)) {
                ci.cancel();
            }
        }
    }
}
