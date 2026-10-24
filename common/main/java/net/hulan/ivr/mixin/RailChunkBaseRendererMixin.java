package net.hulan.ivr.mixin;

import net.hulan.ivr.render_promotion.itf.ANTERailChunkBounds;
import net.hulan.ivr.render_promotion.itf.ANTERailChunkGeometrySource;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.mesh.QuadSpec;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 为 ANTE 轨道块渲染器暴露基类中的包围盒。
 * 同时覆盖 Fabric 与 Forge 两套类名。
 */
@Pseudo
@Mixin(targets = {
        "fabric.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase",
        "forge.cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase"
}, remap = false)
public abstract class RailChunkBaseRendererMixin implements ANTERailChunkBounds, ANTERailChunkGeometrySource {

    /**
     * 映射 ANTE 轨道块已有的世界空间包围盒。
     * 入队前的可见性判断直接使用该字段。
     */
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

    /**
     * Extract only already materialized pure-data snapshots.  ANTE versions
     * that keep packed Sowcer buffers or model references are intentionally
     * rejected; reading or rebuilding those objects off-thread is unsafe.
     */
    @Override
    public List<VertexMesh.Vertex> ivr$copyRailGeometry() {
        ArrayList<VertexMesh.Vertex> result = new ArrayList<>();
        boolean recognizedSource = false;
        for (Class<?> type = getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (RuntimeException inaccessible) {
                return null;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(this);
                } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (fieldName.contains("vertex") || fieldName.contains("quad")
                        || fieldName.contains("geometry") || fieldName.contains("mesh")) {
                    recognizedSource = true;
                    iVR$appendPureVertices(value, result);
                }
                if (result.size() > 65536) return null;
            }
        }
        return recognizedSource ? List.copyOf(result) : null;
    }

    @Unique
    private static void iVR$appendPureVertices(Object value, List<VertexMesh.Vertex> result) {
        if (value == null || value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>) return;
        if (value instanceof VertexMesh.Vertex vertex) {
            result.add(vertex);
            return;
        }
        if (value instanceof QuadSpec quad) {
            result.addAll(quad.expand());
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element instanceof VertexMesh.Vertex vertex) result.add(vertex);
                else if (element instanceof QuadSpec quad) result.addAll(quad.expand());
                else return;
            }
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray() && type.getComponentType() != null
                && (VertexMesh.Vertex.class.isAssignableFrom(type.getComponentType())
                || QuadSpec.class.isAssignableFrom(type.getComponentType()))) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object element = Array.get(value, index);
                if (element instanceof VertexMesh.Vertex vertex) result.add(vertex);
                else if (element instanceof QuadSpec quad) result.addAll(quad.expand());
            }
        }
    }
}
