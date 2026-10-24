package net.hulan.ivr.render_promotion.cache;

import net.hulan.ivr.render_promotion.itf.ANTERailChunkGeometrySource;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Tracks completed ANTE rail chunk builds by identity and render revision.
 * ANTE owns the actual Sowcer VBO/instance buffer; this class only prevents a
 * duplicate CPU rebuild and invalidates that ownership when rail structure or
 * resources change.
 */
public final class NTERailChunkCache {

    private static final Map<Object, Entry> BUILT = new WeakHashMap<>();
    private static final Map<Object, Long> RETRY_AFTER = new WeakHashMap<>();
    private static final Map<Object, VertexMeshKey> ASYNC_KEYS = new WeakHashMap<>();
    /** Chunks whose release exposes no safe pure-data bridge. */
    private static final Map<Object, String> NATIVE_ONLY = new WeakHashMap<>();
    private static final Set<String> DIAGNOSTIC_TYPES = new HashSet<>();
    private static final long RETRY_DELAY_NANOS = 250_000_000L;

    private NTERailChunkCache() { }

    public static synchronized boolean shouldSkipRebuild(Object chunk) {
        if (chunk == null) return false;
        Entry entry = BUILT.get(chunk);
        if (entry == null || entry.revision != RenderDataRevision.current()) return false;
        // ANTE sets these flags around its own Sowcer buffers.  Never skip a
        // dirty or not-yet-uploaded chunk even when the global revision is the
        // same; this was the source of stale rails after an in-place edit.
        if (booleanField(chunk, "isDirty", true) || !booleanField(chunk, "bufferBuilt", false)) {
            BUILT.remove(chunk);
            return false;
        }
        Long retryAfter = RETRY_AFTER.get(chunk);
        return retryAfter == null || System.nanoTime() >= retryAfter;
    }

    public static synchronized void markBuilt(Object chunk) {
        if (chunk != null) {
            BUILT.put(chunk, new Entry(RenderDataRevision.current()));
            RETRY_AFTER.remove(chunk);
        }
    }

    /**
     * Schedules a pure ANTE snapshot for the shared bounded worker pool.  The
     * native ANTE rebuild remains authoritative until a version adapter can
     * consume the completed mesh; this method never invokes ANTE or GL off the
     * render thread.  Unknown ANTE releases return false and use native CPU.
     */
    public static boolean scheduleAsyncSnapshot(Object chunk) {
        if (isNativeOnly(chunk)) return false;
        // ANTE still owns the authoritative Sowcer/VBO rebuild and no typed
        // consumer exists for the "nte-rail" namespace. Taking a complete
        // reflection snapshot here would therefore duplicate the native CPU
        // work without changing what is drawn. Keep the native path active
        // until a version adapter explicitly installs a consumer.
        try {
            if (!net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch.hasTypedConsumer("nte-rail")) {
                markNativeOnly(chunk, "no typed async mesh consumer");
                return false;
            }
        } catch (IllegalStateException unavailable) {
            markNativeOnly(chunk, "async consumer queried off render thread");
            return false;
        }
        if (!(chunk instanceof ANTERailChunkGeometrySource source)) {
            markNativeOnly(chunk, "geometry interface is unavailable");
            return false;
        }
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return false;
        List<VertexMesh.Vertex> vertices;
        try {
            vertices = source.ivr$copyRailGeometry();
        } catch (RuntimeException failure) {
            markNativeOnly(chunk, "pure geometry snapshot threw " + failure.getClass().getSimpleName());
            return false;
        }
        if (vertices == null) {
            markNativeOnly(chunk, "release has no supported pure-data fields");
            return false;
        }
        if (vertices.isEmpty()) {
            // The bridge is known, but ANTE has not materialized this chunk's
            // geometry yet.  Keep the native rebuild authoritative and retry
            // later instead of permanently disabling a valid adapter.
            markBuildFailed(chunk);
            return false;
        }
        VertexMeshKey key;
        VertexMeshKey staleKey = null;
        long revision = RenderDataRevision.current();
        synchronized (NTERailChunkCache.class) {
            VertexMeshKey existing = ASYNC_KEYS.get(chunk);
            if (existing != null && existing.revision() != revision) {
                staleKey = existing;
                ASYNC_KEYS.remove(chunk);
            }
            key = ASYNC_KEYS.computeIfAbsent(chunk, ignored -> new VertexMeshKey(
                    "nte-rail:" + System.identityHashCode(chunk), 0,
                    revision, "", "nte-rail", 0));
        }
        if (staleKey != null) {
            try {
                RenderCacheLifecycle.invalidate(staleKey);
            } catch (RuntimeException ignored) {
                // The generation gate rejects stale results when invalidation
                // is observed from a non-client lifecycle callback.
            }
        }
        try {
            RenderCacheLifecycle.request(key, vertices);
            return RenderCacheLifecycle.isPending(key) || RenderCacheLifecycle.get(key) != null;
        } catch (RuntimeException failure) {
            markBuildFailed(chunk);
            return false;
        }
    }

    /** A failed native rebuild must not be retried every render frame. */
    public static synchronized void markBuildFailed(Object chunk) {
        if (chunk != null) {
            BUILT.remove(chunk);
            RETRY_AFTER.put(chunk, System.nanoTime() + RETRY_DELAY_NANOS);
        }
    }

    public static synchronized void invalidate(Object chunk) {
        if (chunk != null) {
            BUILT.remove(chunk);
            RETRY_AFTER.remove(chunk);
            VertexMeshKey key = ASYNC_KEYS.remove(chunk);
            if (key != null) {
                try {
                    RenderCacheLifecycle.invalidate(key);
                } catch (RuntimeException ignored) {
                    // Invalidation can be called by a non-client lifecycle
                    // callback; the world generation gate still rejects it.
                }
            }
        }
        RenderDataRevision.bump();
    }

    public static synchronized void clear() {
        BUILT.clear();
        RETRY_AFTER.clear();
        ASYNC_KEYS.clear();
        NATIVE_ONLY.clear();
        DIAGNOSTIC_TYPES.clear();
    }

    private static synchronized boolean isNativeOnly(Object chunk) {
        return chunk != null && NATIVE_ONLY.containsKey(chunk);
    }

    private static void markNativeOnly(Object chunk, String reason) {
        if (chunk == null) return;
        String type = chunk.getClass().getName();
        VertexMeshKey staleKey;
        boolean firstDiagnostic;
        synchronized (NTERailChunkCache.class) {
            NATIVE_ONLY.put(chunk, reason);
            staleKey = ASYNC_KEYS.remove(chunk);
            firstDiagnostic = DIAGNOSTIC_TYPES.add(type);
        }
        if (staleKey != null) {
            try {
                RenderCacheLifecycle.invalidate(staleKey);
            } catch (RuntimeException ignored) {
                // A non-client callback cannot touch the render-thread cache;
                // the generation gate still rejects any stale completion.
            }
        }
        if (firstDiagnostic) {
            System.err.println("[IVR] ANTE " + type
                    + " has no safe async geometry bridge (" + reason + "); using native CPU rebuild");
        }
    }

    private static boolean booleanField(Object target, String name, boolean fallback) {
        try {
            Field field = null;
            for (Class<?> type = target.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // Continue through ANTE's base class hierarchy.
                }
            }
            if (field == null || field.getType() != boolean.class) return fallback;
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Unknown versions must rebuild conservatively.
            return fallback;
        }
    }

    private record Entry(long revision) { }
}
