package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.visibility.RailVisibility;
import net.hulan.ivr.render_promotion.itf.ANTERailChunkBounds;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ANTE 轨道块进入批处理队列前执行可见性检查。
 * 同时匹配两种批处理模式和 Fabric、Forge 类名。
 */
@Pseudo
@Mixin(targets = {
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk",
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk"},
        remap = false)
public abstract class RailChunkBaseVisibilityMixin {

    /**
     * 根据轨道块包围盒决定是否取消入队。
     * 实际距离、视锥和遮挡判定由 RailVisibility 负责。
     */
    @Inject(method = "enqueue", at = @At("HEAD"), cancellable = true, remap = false)
    private void ivr$enqueueRailChunkIfVisible(CallbackInfo ci) {
        // Optional ANTE releases may not expose a stable bounds interface. Keep
        // native rendering active when the boundary is unavailable or dynamic.
        if (!(this instanceof ANTERailChunkBounds boundsProvider)) return;
        AABB bounds = boundsProvider.ivr$getRailChunkBounds();
        if (bounds == null) return;
        String name = getClass().getName().toLowerCase(java.util.Locale.ROOT);
        RenderDebugStats.Category category = name.contains("eyecandy") || name.contains("eye_candy")
                ? RenderDebugStats.Category.EYE_CANDY : RenderDebugStats.Category.RAILBOX;
        RenderDebugStats.Category previous = RenderDebugStats.pushCategory(category);
        boolean render;
        long started = RenderDebugStats.startTiming();
        try {
            render = RailVisibility.getInstance().shouldRenderBox(bounds);
        } finally {
            RenderDebugStats.popCategory(previous);
        }
        RenderDebugStats.recordOutcome(category, render, RenderDebugStats.elapsedSince(started));
        if (!render) {
            ci.cancel();
        }
    }
}
