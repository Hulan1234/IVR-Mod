package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.SignLengthCache;
import net.hulan.ivr.render_promotion.occlusion.OcclusionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 监听客户端方块变更，通知遮挡缓存局部失效。
 * 服务端世界与未成功的 setBlock 调用不触发通知。
 */
@Mixin(Level.class)
public abstract class VisibilityBlockChangeMixin {
    /**
     * 处理不带递归深度参数的 setBlock 重载。
     * 只把成功修改的坐标交给统一失效入口。
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void ivr$invalidateOcclusionShort(BlockPos pos, BlockState state, int flags,
                                               CallbackInfoReturnable<Boolean> ci) {
        ivr$invalidate(pos, ci.getReturnValue());
    }

    /**
     * 处理带递归深度参数的 setBlock 重载。
     * 在原方法返回后按实际修改结果决定是否失效。
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void ivr$invalidateOcclusion(BlockPos pos, BlockState state, int flags, int depth,
                                         CallbackInfoReturnable<Boolean> ci) {
        ivr$invalidate(pos, ci.getReturnValue());
    }

    /**
     * 将成功的客户端方块变更传给遮挡调度器。
     * 缓存失效的范围由调度器按该坐标确定。
     */
    @Unique
    private void ivr$invalidate(BlockPos pos, boolean changed) {
        if (changed && ((Level) (Object) this).isClientSide) {
            SignLengthCache.invalidate();
            OcclusionScheduler.onBlockChanged((Level) (Object) this, pos);
        }
    }
}
