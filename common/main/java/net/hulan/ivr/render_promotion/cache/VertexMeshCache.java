package net.hulan.ivr.render_promotion.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread-owned LRU with conservative CPU byte accounting. */
public final class VertexMeshCache {
    private final int maxEntries;
    private final long maxBytes;
    private final Map<VertexMeshKey, VertexMesh> meshes = new LinkedHashMap<>(16, 0.75F, true);
    private long bytes;

    public VertexMeshCache(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid budget");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public VertexMesh get(VertexMeshKey key) {
        return meshes.get(key);
    }

    public void put(VertexMeshKey key, VertexMesh mesh) {
        if (mesh.byteSize() > maxBytes) return;
        VertexMesh old = meshes.put(key, mesh);
        if (old != null) bytes -= old.byteSize();
        bytes += mesh.byteSize();
        while (meshes.size() > maxEntries || bytes > maxBytes) {
            VertexMeshKey eldest = meshes.keySet().iterator().next();
            bytes -= meshes.remove(eldest).byteSize();
        }
    }

    public void remove(VertexMeshKey key) {
        VertexMesh old = meshes.remove(key);
        if (old != null) bytes -= old.byteSize();
    }

    public void clear() { meshes.clear(); bytes = 0; }
}
