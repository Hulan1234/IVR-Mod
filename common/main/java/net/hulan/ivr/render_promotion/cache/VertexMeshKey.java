package net.hulan.ivr.render_promotion.cache;

import java.util.Objects;

/** 标识局部几何、生命周期版本、几何版本和重放所需的材质约定。 */
public record VertexMeshKey(String model, int variant, long revision, long geometryRevision,
                            String texture, String material, int lod) {

    public VertexMeshKey(String model, int variant, long revision) {
        this(model, variant, revision, 0L, "", "", 0);
    }

    public VertexMeshKey(String model, int variant, long revision,
                         String texture, String material, int lod) {
        this(model, variant, revision, 0L, texture, material, lod);
    }

    public VertexMeshKey {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(material, "material");
    }
}
