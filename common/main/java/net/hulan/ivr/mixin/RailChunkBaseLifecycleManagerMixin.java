package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将 ANTE 的 CPU 网格重建状态与轨道结构版本关联。 */
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
    @Inject(method = {"addRail", "removeRail", "setBoundingBox"}, at = @At("TAIL"),
            remap = false, require = 0)
    private void ivr$invalidateStructure(CallbackInfo ci) {
        RenderDataRevision.bump();
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false, require = 0)
    private void ivr$releaseChunk(CallbackInfo ci) {
        RenderDataRevision.bump();
    }
}
