package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.texture.EyeCandyPreloader;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.hulan.ivr.client.IVRConfig;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caches only EyeCandy geometry that proves stable across two consecutive renders.
 * The original renderer remains the source of truth while a candidate is being learned.
 */
public final class EyeCandyMeshCache {
    private static final int MAX_ENTRIES = 256;
    private static final long VALIDATION_INTERVAL = 60L;
    private static final Map<VertexMeshKey, Entry> ENTRIES =
            new LinkedHashMap<>(16, .75F, true);
    private static final Map<VertexMeshKey, Candidate> CANDIDATES =
            new LinkedHashMap<>(16, .75F, true);
    private static Session session;
    private static long frame;

    private EyeCandyMeshCache() {
    }

    /** Called at the renderer head. Returns true only when cached geometry was emitted. */
    public static synchronized boolean begin(BlockEntity entity, PoseStack matrices,
                                              MultiBufferSource buffers, int light, int overlay) {
        frame++;
        session = null;
        if (!IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization) {
            return false;
        }
        long expectedGeneration = RenderCacheLifecycle.generation();
        VertexMeshKey key = EyeCandyPreloader.stableMeshKey(entity).orElse(null);
        if (key == null) {
            return false;
        }
        if (IVRConfig.cpuOptimization && !IVRConfig.gpuOptimization
                && !RenderCacheLifecycle.shouldAttemptCpuCache(key)) {
            return false;
        }
        Entry entry = ENTRIES.get(key);
        if (entry != null && frame % VALIDATION_INTERVAL != 0L) {
            try {
                if (RenderCacheLifecycle.getPrepared(key) == null) {
                    if (RenderCacheLifecycle.publishImmediateIfCurrent(
                            key, entry.mesh(), expectedGeneration) == null) {
                        ENTRIES.remove(key);
                        return false;
                    }
                }
                VertexConsumer consumer = buffers.getBuffer(entry.layer());
                GpuInstanceBatch.ConsumptionStatus status;
                if (entry.texture() != null) {
                    status = GpuInstanceBatch.submitOrFallbackStatus(entry.layer(), entry.texture(), key,
                            entry.mesh(), matrices, light, overlay, consumer);
                } else {
                    if (!IVRConfig.cpuOptimization) {
                        session = new Session(key, null, entity,
                                EyeCandyPreloader.textureHint(entity).orElse(null), expectedGeneration);
                        return false;
                    }
                    entry.mesh().replay(matrices, consumer, light, overlay);
                    status = GpuInstanceBatch.ConsumptionStatus.CONSUMED_CPU;
                }
                if (status == GpuInstanceBatch.ConsumptionStatus.PARTIAL_OR_UNKNOWN
                        || status == GpuInstanceBatch.ConsumptionStatus.NOT_CONSUMED) {
                    ENTRIES.remove(key);
                    RenderCacheLifecycle.removeIfCurrent(key, entry.mesh(), expectedGeneration);
                    session = new Session(key, null, entity,
                            EyeCandyPreloader.textureHint(entity).orElse(null), expectedGeneration);
                    return false;
                }
                return true;
            } catch (RuntimeException | LinkageError ignored) {
                ENTRIES.remove(key);
                RenderCacheLifecycle.removeIfCurrent(key, entry.mesh(), expectedGeneration);
            }
        }
        session = new Session(key, entry, entity,
                entry == null ? EyeCandyPreloader.textureHint(entity).orElse(null) : entry.texture(),
                expectedGeneration);
        return false;
    }

    /** Wraps the actual renderer buffer acquisition so only emitted vertices are recorded. */
    public static synchronized VertexConsumer wrap(RenderType layer, VertexConsumer delegate) {
        if (session == null || layer == null || delegate == null) {
            return delegate;
        }
        if (session.layer() == null) {
            if (session.texture() == null
                    || !GpuInstanceBatch.isSupportedGpuLayer(layer, session.texture())) {
                session.unsafe = true;
                return delegate;
            }
            session.layer = layer;
            session.recorder = new VertexRecorder();
            return new TeeVertexConsumer(delegate, session.recorder);
        }
        session.unsafe = true;
        return delegate;
    }

    /** Called at the renderer return. The original render already emitted this frame. */
    public static synchronized void finish() {
        Session current = session;
        session = null;
        if (current == null || current.unsafe || current.recorder == null
                || current.layer() == null || current.revision() != RenderDataRevision.current()
                || current.world() != Minecraft.getInstance().level) {
            return;
        }
        List<VertexMesh.Vertex> vertices = current.recorder.snapshot();
        if (vertices.isEmpty() || !current.recorder.hasLight()) {
            return;
        }
        VertexMesh mesh = new VertexMesh(vertices);
        if (!hasStableColor(mesh)) {
            if (current.entry() != null) {
                removeEntryIfSame(current.key(), current.entry().mesh());
                RenderCacheLifecycle.removeIfCurrent(current.key(), current.entry().mesh(),
                        current.generation());
            }
            CANDIDATES.remove(current.key());
            return;
        }
        if (current.entry() != null) {
            if (!current.entry().mesh().vertices().equals(mesh.vertices())) {
                removeEntryIfSame(current.key(), current.entry().mesh());
                RenderCacheLifecycle.removeIfCurrent(current.key(), current.entry().mesh(),
                        current.generation());
            }
            return;
        }
        Candidate previous = CANDIDATES.get(current.key());
        if (previous == null || !previous.mesh().vertices().equals(mesh.vertices())
                || previous.layer() != current.layer()) {
            CANDIDATES.put(current.key(), new Candidate(mesh, current.layer()));
            trim(CANDIDATES);
            return;
        }
        CANDIDATES.remove(current.key());
        VertexMesh published = RenderCacheLifecycle.publishImmediateIfCurrent(
                current.key(), mesh, current.generation());
        if (published == null
                || current.revision() != RenderDataRevision.current()
                || current.world() != Minecraft.getInstance().level
                || current.generation() != RenderCacheLifecycle.generation()) {
            RenderCacheLifecycle.removeIfSame(current.key(), mesh);
            return;
        }
        ENTRIES.put(current.key(), new Entry(mesh, current.layer(),
                EyeCandyPreloader.textureHint(current.entity()).orElse(null)));
        trimEntries();
    }

    public static synchronized void cancel() {
        session = null;
    }

    public static synchronized void clear() {
        session = null;
        CANDIDATES.clear();
        ENTRIES.clear();
    }

    private static void removeEntryIfSame(VertexMeshKey key, VertexMesh mesh) {
        Entry entry = ENTRIES.get(key);
        if (entry != null && entry.mesh() == mesh) {
            ENTRIES.remove(key);
        }
    }

    private static <K, V> void trim(Map<K, V> map) {
        while (map.size() > MAX_ENTRIES) {
            Iterator<K> iterator = map.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static void trimEntries() {
        while (ENTRIES.size() > MAX_ENTRIES) {
            Iterator<VertexMeshKey> iterator = ENTRIES.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            VertexMeshKey key = iterator.next();
            iterator.remove();
            RenderCacheLifecycle.invalidate(key);
        }
    }

    /** Cached geometry cannot safely replay a dynamic vertex tint as an instance attribute. */
    private static boolean hasStableColor(VertexMesh mesh) {
        for (VertexMesh.Vertex vertex : mesh.vertices()) {
            if (vertex.color() != -1) {
                return false;
            }
        }
        return true;
    }

    private static final class Session {
        private final VertexMeshKey key;
        private final Entry entry;
        private final BlockEntity entity;
        private final ResourceLocation texture;
        private final long revision;
        private final long generation;
        private final Object world;
        private RenderType layer;
        private VertexRecorder recorder;
        private boolean unsafe;

        private Session(VertexMeshKey key, Entry entry, BlockEntity entity,
                        ResourceLocation texture, long generation) {
            this.key = key;
            this.entry = entry;
            this.entity = entity;
            this.texture = texture;
            this.revision = RenderDataRevision.current();
            this.generation = generation;
            this.world = Minecraft.getInstance().level;
        }

        private VertexMeshKey key() {
            return key;
        }

        private Entry entry() {
            return entry;
        }

        private RenderType layer() {
            return layer;
        }

        private VertexRecorder recorder() {
            return recorder;
        }

        private BlockEntity entity() {
            return entity;
        }

        private ResourceLocation texture() {
            return texture;
        }

        private long revision() {
            return revision;
        }

        private long generation() {
            return generation;
        }

        private Object world() {
            return world;
        }
    }

    private record Candidate(VertexMesh mesh, RenderType layer) {
    }

    private record Entry(VertexMesh mesh, RenderType layer, ResourceLocation texture) {
    }

    private static final class TeeVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final VertexRecorder recorder;

        private TeeVertexConsumer(VertexConsumer delegate, VertexRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public @NotNull VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            recorder.vertex(x, y, z);
            return this;
        }

        @Override
        public @NotNull VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, alpha);
            recorder.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public @NotNull VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            recorder.uv(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            recorder.overlayCoords(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            recorder.uv2(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            recorder.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
            recorder.endVertex();
        }

        public void defaultColor(int red, int green, int blue, int alpha) {
            VertexConsumerCompat.defaultColor(delegate, red, green, blue, alpha);
            recorder.defaultColor(red, green, blue, alpha);
        }

        public void unsetDefaultColor() {
            VertexConsumerCompat.unsetDefaultColor(delegate);
            recorder.unsetDefaultColor();
        }
    }
}
