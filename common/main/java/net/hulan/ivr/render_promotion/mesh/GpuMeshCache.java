package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lifecycle shim for the optional GPU path.
 * Version adapters own their resident buffers because VertexBuffer and raw GL
 * APIs differ between Minecraft versions. A second generic upload queue would
 * either call an unsupported adapter or duplicate ownership. The actual mesh
 * is supplied to GpuInstanceBatch and uploaded by that adapter on the render
 * thread.
 */
public final class GpuMeshCache {
    private static final int MAX_READY_ENTRIES = 256;
    private static final long MAX_READY_BYTES = 8L * 1024 * 1024;
    private static final Map<VertexMeshKey, Entry> READY = new LinkedHashMap<>(16, .75F, true);
    private static long readyBytes;

    private GpuMeshCache() { }

    /** Publishes CPU-built geometry for the version adapter's lazy GPU upload. */
    public static void publish(VertexMeshKey key, VertexMesh mesh) {
        requireRenderThread();
        if (key == null || mesh == null) return;
        trimToBudget();
        long bytes = mesh.byteSize();
        Entry old = READY.put(key, new Entry(mesh, bytes));
        if (old != null) readyBytes -= old.bytes;
        readyBytes += bytes;
        trimToBudget();
    }

    /** Compatibility alias for callers that use ordinary cache terminology. */
    public static void put(VertexMeshKey key, VertexMesh mesh) {
        publish(key, mesh);
    }

    /** Returns a published mesh and refreshes its LRU position. */
    public static VertexMesh get(VertexMeshKey key) {
        requireRenderThread();
        trimToBudget();
        Entry entry = READY.get(key);
        return entry == null ? null : entry.mesh;
    }

    /** A submission may use only geometry published by the client cache. */
    public static boolean isPublished(VertexMeshKey key, VertexMesh mesh) {
        return get(key) == mesh;
    }

    /** Drops the published reference; GL resources remain owned by the version backend. */
    public static void remove(VertexMeshKey key) {
        requireRenderThread();
        Entry old = READY.remove(key);
        if (old != null) readyBytes -= old.bytes;
    }

    public static void removeIfSame(VertexMeshKey key, VertexMesh mesh) {
        requireRenderThread();
        Entry entry = READY.get(key);
        if (entry == null || entry.mesh != mesh) {
            return;
        }
        READY.remove(key);
        readyBytes -= entry.bytes;
    }

    /** Releases no resources directly; the active version backend owns them. */
    public static void clear() {
        requireRenderThread();
        READY.clear();
        readyBytes = 0L;
    }

    /** Version backends upload lazily when a resident draw group is submitted. */
    public static void drain() {
        requireRenderThread();
        trimToBudget();
    }

    /** Trims entries after a dynamic memory-budget change. */
    public static void trim() {
        requireRenderThread();
        trimToBudget();
    }

    public static int size() {
        requireRenderThread();
        trimToBudget();
        return READY.size();
    }

    public static long bytes() {
        requireRenderThread();
        trimToBudget();
        return readyBytes;
    }

    private static void trimToBudget() {
        long budget = Math.min(MAX_READY_BYTES, IVRMemoryBudget.cpuMeshBudgetBytes());
        while (!READY.isEmpty() && (READY.size() > MAX_READY_ENTRIES || readyBytes > budget)) {
            Map.Entry<VertexMeshKey, Entry> eldest = READY.entrySet().iterator().next();
            READY.remove(eldest.getKey());
            readyBytes -= eldest.getValue().bytes;
        }
        if (readyBytes < 0L) readyBytes = 0L;
    }

    private record Entry(VertexMesh mesh, long bytes) { }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThreadOrInit()) throw new IllegalStateException("Render thread required");
    }
}
