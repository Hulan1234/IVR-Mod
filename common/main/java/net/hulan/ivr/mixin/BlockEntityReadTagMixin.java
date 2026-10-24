package net.hulan.ivr.mixin;

import mtr.mappings.BlockEntityMapper;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = BlockEntityMapper.class, remap = false)
public abstract class BlockEntityReadTagMixin {

    @Inject(method = "readCompoundTag", at = @At("RETURN"), remap = false, require = 0)
    private void ivr$markReadRenderDataDirty(CallbackInfo ci) {
        RenderDataRevision.markDirty((BlockEntity) (Object) this);
    }
}
