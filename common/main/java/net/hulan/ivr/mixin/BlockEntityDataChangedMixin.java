package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityDataChangedMixin {

    @Inject(method = "setChanged*", at = @At("RETURN"))
    private void ivr$markInstanceRenderDataDirty(CallbackInfo ci) {
        RenderDataRevision.markDirty((BlockEntity) (Object) this);
    }
}
