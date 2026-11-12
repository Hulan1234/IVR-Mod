package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.itf.ANTERailChunkBounds;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 为 ANTE 轨道块渲染器暴露基类中的包围盒。
 * 同时覆盖 Fabric 与 Forge 两套类名。
 */
@Pseudo
@Mixin(targets = {
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase"
}, remap = false)
public abstract class RailChunkBaseRendererMixin implements ANTERailChunkBounds {

    /** 映射 ANTE 已有的世界空间包围盒，供入队前的可见性判断使用。 */
    @Shadow(remap = false)
    public AABB boundingBox;

    /**
     * 将基类包围盒提供给统一的轨道块接口。
     * 此处不重新计算几何边界。
     */
    @Override
    public AABB ivr$getRailChunkBounds() {
        return boundingBox;
    }

}
