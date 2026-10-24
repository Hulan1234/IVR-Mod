package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.render_promotion.cache.VertexMesh;

import java.util.List;

/** Pure immutable rectangle description; safe to expand on a worker thread. */
public record QuadSpec(float minX, float minY, float maxX, float maxY, float z,
                       int color, float nx, float ny, float nz) {
    public QuadSpec {
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX)
                || !Float.isFinite(maxY) || !Float.isFinite(z)) throw new IllegalArgumentException("Non-finite quad");
    }
    public List<VertexMesh.Vertex> expand() {
        return List.of(new VertexMesh.Vertex(minX, minY, z, 0, 1, nx, ny, nz, color),
                new VertexMesh.Vertex(maxX, minY, z, 1, 1, nx, ny, nz, color),
                new VertexMesh.Vertex(maxX, maxY, z, 1, 0, nx, ny, nz, color),
                new VertexMesh.Vertex(minX, maxY, z, 0, 0, nx, ny, nz, color));
    }
}
