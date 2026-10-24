package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
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
    private static final int MAX_READY = 256;
    private static final Map<VertexMeshKey, VertexMesh> READY = new LinkedHashMap<>(16, .75F, true);
    private GpuMeshCache() { }

    /** Publishes CPU-built geometry for the version adapter's lazy GPU upload. */
    public static void publish(VertexMeshKey key, VertexMesh mesh) {
        requireRenderThread();
        if (key == null || mesh == null) return;
        READY.put(key, mesh);
        while (READY.size() > MAX_READY) READY.remove(READY.keySet().iterator().next());
    }

    /** A submission may use only geometry published by the client cache. */
    public static boolean isPublished(VertexMeshKey key, VertexMesh mesh) {
        requireRenderThread();
        return READY.get(key) == mesh;
    }

    /** Drops the published reference; GL resources remain owned by the version backend. */
    public static void remove(VertexMeshKey key) {
        requireRenderThread();
        READY.remove(key);
    }

    /** Releases no resources directly; the active version backend owns them. */
    public static void clear() {
        requireRenderThread();
        READY.clear();
    }

    /** Version backends upload lazily when a resident draw group is submitted. */
    public static void drain() {
        requireRenderThread();
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThreadOrInit()) throw new IllegalStateException("Render thread required");
    }
}
