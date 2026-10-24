package net.hulan.ivr.render_promotion.cache;

import java.util.Objects;

/** Identifies local geometry and the material contract used to replay it. */
public record VertexMeshKey(String model, int variant, long revision,
                            String texture, String material, int lod) {

    public VertexMeshKey(String model, int variant, long revision) {
        this(model, variant, revision, "", "", 0);
    }

    public VertexMeshKey {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(material, "material");
    }
}
