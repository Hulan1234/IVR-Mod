package net.hulan.ivr.render_promotion.itf;

import net.minecraft.world.phys.AABB;

/**
 * 可选 ANTE 轨道块 Mixin 暴露的内部边界接口。
 * 供入队钩子读取对应块的世界空间包围盒。
 */
public interface ANTERailChunkBounds {

    /**
     * 返回 ANTE 渲染器已持有的轨道块包围盒。
     * 可见性策略据此执行距离、视锥和遮挡判断。
     */
    AABB ivr$getRailChunkBounds();
}
