package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.NTERailChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps ANTE's CPU mesh rebuilds tied to the rail structure revision. */
@Pseudo
@Mixin(targets = {
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase",
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk",
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk"},
        remap = false)
public abstract class RailChunkBaseLifecycleManagerMixin {
    @Inject(method = "rebuildBuffer", at = @At("HEAD"),
            remap = false, require = 0)
    private void ivr$scheduleAsyncSnapshot(CallbackInfo ci) {
        // The native method always continues.  ANTE owns its render-thread
        // Sowcer/VBO state, so an optional CPU snapshot must never cancel or
        // duplicate the authoritative rebuild.  Once a clean native buffer
        // is known, skip the redundant snapshot attempt for this call.
        if (!NTERailChunkCache.shouldSkipRebuild(this)) {
            NTERailChunkCache.scheduleAsyncSnapshot(this);
        }
    }

    @Inject(method = "rebuildBuffer", at = @At("RETURN"), remap = false, require = 0)
    private void ivr$markRebuilt(CallbackInfo ci) {
        NTERailChunkCache.markBuilt(this);
    }

    @Inject(method = {"addRail", "removeRail", "setBoundingBox"}, at = @At("TAIL"),
            remap = false, require = 0)
    private void ivr$invalidateStructure(CallbackInfo ci) {
        NTERailChunkCache.invalidate(this);
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false, require = 0)
    private void ivr$releaseChunk(CallbackInfo ci) {
        NTERailChunkCache.invalidate(this);
    }
}
