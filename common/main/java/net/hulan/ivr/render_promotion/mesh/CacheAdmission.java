package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.debug.RenderDebugStats;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps unstable mesh keys from repeatedly paying capture/build costs.  The
 * table is render-thread owned and deliberately small; a disabled key can be
 * retried after a short cooldown so a later revision can recover it.
 */
final class CacheAdmission {
    private static final int MAX_ENTRIES = 512;
    private static final int MIN_ATTEMPTS = 8;
    private static final long DISABLE_NANOS = 2_000_000_000L;
    private static final Map<VertexMeshKey, Entry> ENTRIES =
            new LinkedHashMap<>(32, .75F, true);
    private static final int MAX_SCOPE_ENTRIES = 256;
    private static final Map<Class<?>, ScopeEntry> SCOPE_ENTRIES = new IdentityHashMap<>();

    private CacheAdmission() { }

    static boolean shouldAttempt(VertexMeshKey key) {
        if (!IVRConfig.cpuOptimization || IVRConfig.gpuOptimization) return true;
        if (key == null) return false;
        Entry entry = ENTRIES.get(key);
        return entry == null || entry.disabledUntilNanos <= System.nanoTime();
    }

    static boolean shouldAttemptScope(Class<?> callbackType) {
        if (!IVRConfig.cpuOptimization || IVRConfig.gpuOptimization || callbackType == null) return true;
        ScopeEntry entry = SCOPE_ENTRIES.get(callbackType);
        if (entry == null) return true;
        long now = System.nanoTime();
        if (entry.disabledUntilNanos > now) return false;
        if (entry.disabledUntilNanos != 0L) {
            entry.hits = 0;
            entry.misses = 0;
            entry.disabledUntilNanos = 0L;
        }
        return true;
    }

    static void recordScopeHit(Class<?> callbackType) {
        if (!IVRConfig.cpuOptimization || IVRConfig.gpuOptimization || callbackType == null) return;
        ScopeEntry entry = scopeEntry(callbackType);
        entry.hits++;
        entry.disabledUntilNanos = 0L;
    }

    static void recordScopeMiss(Class<?> callbackType) {
        if (!IVRConfig.cpuOptimization || IVRConfig.gpuOptimization || callbackType == null) return;
        ScopeEntry entry = scopeEntry(callbackType);
        entry.misses++;
        int observations = entry.hits + entry.misses;
        if (observations >= MIN_ATTEMPTS && entry.hits * 2L < observations) {
            entry.disabledUntilNanos = System.nanoTime() + DISABLE_NANOS;
            RenderDebugStats.recordMeshCacheReject();
        }
    }

    static void recordAttempt(VertexMeshKey key) {
        if (key == null) return;
        Entry entry = entry(key);
        entry.attempts++;
        if (IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization
                && entry.attempts >= MIN_ATTEMPTS && entry.hits * 2L < entry.attempts) {
            entry.disabledUntilNanos = System.nanoTime() + DISABLE_NANOS;
            RenderDebugStats.recordMeshCacheReject();
        }
    }

    static void recordHit(VertexMeshKey key) {
        if (key == null) return;
        Entry entry = entry(key);
        entry.hits++;
        entry.disabledUntilNanos = 0L;
        RenderDebugStats.recordMeshCacheHit();
    }

    static void recordMiss(VertexMeshKey key) {
        if (key != null) RenderDebugStats.recordMeshCacheMiss();
    }

    static void clear() {
        ENTRIES.clear();
        SCOPE_ENTRIES.clear();
    }

    static void invalidate(VertexMeshKey key) {
        if (key != null) ENTRIES.remove(key);
    }

    private static Entry entry(VertexMeshKey key) {
        Entry entry = ENTRIES.get(key);
        if (entry != null) return entry;
        entry = new Entry();
        ENTRIES.put(key, entry);
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(ENTRIES.keySet().iterator().next());
        }
        return entry;
    }

    private static ScopeEntry scopeEntry(Class<?> callbackType) {
        ScopeEntry entry = SCOPE_ENTRIES.get(callbackType);
        if (entry != null) return entry;
        if (SCOPE_ENTRIES.size() >= MAX_SCOPE_ENTRIES) SCOPE_ENTRIES.clear();
        entry = new ScopeEntry();
        SCOPE_ENTRIES.put(callbackType, entry);
        return entry;
    }

    private static final class Entry {
        private int attempts;
        private int hits;
        private long disabledUntilNanos;
    }

    private static final class ScopeEntry {
        private int hits;
        private int misses;
        private long disabledUntilNanos;
    }
}
