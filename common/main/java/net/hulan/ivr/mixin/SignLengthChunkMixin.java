package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.SignLengthCache;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class SignLengthChunkMixin {
    @Inject(method = "drop", at = @At("RETURN"))
    private void ivr$clearUnloadedSignLengths(CallbackInfo ci) {
        SignLengthCache.invalidate();
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void ivr$clearLoadedSignLengths(CallbackInfoReturnable<LevelChunk> ci) {
        SignLengthCache.invalidate();
    }
}
