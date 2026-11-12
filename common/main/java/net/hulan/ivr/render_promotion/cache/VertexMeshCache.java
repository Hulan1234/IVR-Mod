package net.hulan.ivr.render_promotion.cache;

import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
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
        trimToBudget();
        return meshes.get(key);
    }

    public void put(VertexMeshKey key, VertexMesh mesh) {
        trimToBudget();
        long effectiveMaxBytes = Math.min(maxBytes, IVRMemoryBudget.cpuMeshBudgetBytes());
        if (mesh.byteSize() > effectiveMaxBytes) {
            IVRMemoryBudget.recordReject("cpu");
            return;
        }
        VertexMesh old = meshes.put(key, mesh);
        if (old != null) bytes -= old.byteSize();
        bytes += mesh.byteSize();
        while (meshes.size() > maxEntries || bytes > effectiveMaxBytes) {
            VertexMeshKey eldest = meshes.keySet().iterator().next();
            bytes -= meshes.remove(eldest).byteSize();
        }
    }

    public void remove(VertexMeshKey key) {
        VertexMesh old = meshes.remove(key);
        if (old != null) bytes -= old.byteSize();
    }

    public void removeIfSame(VertexMeshKey key, VertexMesh mesh) {
        if (meshes.get(key) != mesh) {
            return;
        }
        remove(key);
    }

    public void clear() { meshes.clear(); bytes = 0; }

    public int size() { trimToBudget(); return meshes.size(); }
    public long bytes() { trimToBudget(); return bytes; }

    private void trimToBudget() {
        long effectiveMaxBytes = Math.min(maxBytes, IVRMemoryBudget.cpuMeshBudgetBytes());
        while (!meshes.isEmpty() && bytes > effectiveMaxBytes) {
            VertexMeshKey eldest = meshes.keySet().iterator().next();
            bytes -= meshes.remove(eldest).byteSize();
        }
    }
}
