package net.hulan.ivr.render_promotion.itf;

import net.hulan.ivr.render_promotion.cache.VertexMesh;

import java.util.List;

/**
 * Optional ANTE bridge.  A version adapter may expose a pure immutable copy
 * of its rail vertices through this interface.  The copy must not reference
 * ANTE objects, world state, VertexBuffers, or OpenGL resources. A
 * {@code null} return means that the release has no supported pure-data
 * bridge and must remain on the native CPU path; an empty list is reserved
 * for a supported bridge with no materialized geometry.
 */
public interface ANTERailChunkGeometrySource {

    List<VertexMesh.Vertex> ivr$copyRailGeometry();
}
