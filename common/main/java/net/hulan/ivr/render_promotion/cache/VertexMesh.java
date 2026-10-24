package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;

/** Immutable local CPU geometry. Replay preserves the caller's layer and draw calls. */
public final class VertexMesh {

    public record Vertex(float x, float y, float z, float u, float v,
                         float nx, float ny, float nz, int color) {
        public Vertex {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(u) || !Float.isFinite(v)
                    || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)) {
                throw new IllegalArgumentException("Non-finite vertex");
            }
        }
    }

    private final List<Vertex> vertices;

    public VertexMesh(List<Vertex> vertices) {
        this.vertices = List.copyOf(vertices);
        if (this.vertices.size() > 65536) throw new IllegalArgumentException("Mesh too large");
    }

    public List<Vertex> vertices() { return vertices; }
    public long byteSize() { return 64L + vertices.size() * 48L; }

    /** Packed light and overlay remain live; no GPU resource is owned by this mesh. */
    public void replay(PoseStack matrices, VertexConsumer consumer, int light, int overlay) {
        PoseStack.Pose pose = matrices.last();
        for (Vertex vertex : vertices) {
            int color = vertex.color();
            consumer.vertex(pose.pose(), vertex.x(), vertex.y(), vertex.z())
                    .color(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24 & 255)
                    .uv(vertex.u(), vertex.v()).overlayCoords(overlay).uv2(light)
                    .normal(pose.normal(), vertex.nx(), vertex.ny(), vertex.nz()).endVertex();
        }
    }
}
