package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.DependenciesChecker;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import net.hulan.ivr.client.IVRConfig;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对单个确定的不透明实体图层执行可选的 VBO 缓存绘制。 */
public final class GpuMeshBackend implements GpuInstanceBatch.Backend {
    /** 尚未审计此映射下旧版 VertexBuffer 的状态恢复。 */
    private static final long MAX_RESIDENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RESIDENT_BUFFERS = 256;
    private static LinkedHashMap<BufferKey, VertexBuffer> RESIDENT = new LinkedHashMap<>(16, .75F, true);
    private static GpuMeshBackend installed;
    private static boolean failed;
    private static volatile boolean disposeRequested;
    private static long residentBytes;
    private static volatile long lifecycleGeneration;
    private static volatile long pendingReleaseGeneration = -1L;
    private static GpuMeshBackend pendingReleaseBackend;
    private static LinkedHashMap<BufferKey, VertexBuffer> pendingReleaseResident;

    private GpuMeshBackend() { }

    public static void installIfSupported() {
        if (disposeRequested && hasCurrentContext()) {
            releaseOnRenderThread(pendingReleaseGeneration, pendingReleaseBackend, pendingReleaseResident);
        }
        if (!IVRConfig.gpuOptimization || disposeRequested || installed != null || failed || !hasCurrentContext()) return;
        try {
            if (DependenciesChecker.isGpuMeshBackendBlocked()) { failed = true; return; }
            installed = new GpuMeshBackend();
            lifecycleGeneration++;
            GpuInstanceBatch.installBackend(installed);
        } catch (RuntimeException | LinkageError unavailable) { failed = true; }
    }

    public static void dispose() {
        if (disposeRequested) return;
        disposeRequested = true;
        GpuInstanceBatch.disableBackend();
        long generation = ++lifecycleGeneration;
        GpuMeshBackend old = installed;
        LinkedHashMap<BufferKey, VertexBuffer> oldResident = RESIDENT;
        pendingReleaseGeneration = generation;
        pendingReleaseBackend = old;
        pendingReleaseResident = oldResident;
        RESIDENT = new LinkedHashMap<>(16, .75F, true);
        residentBytes = 0L;
        Runnable release = () -> releaseOnRenderThread(generation, old, oldResident);
        if (hasCurrentContext()) release.run();
        else RenderSystem.recordRenderCall(release::run);
    }

    private static void releaseOnRenderThread(long generation, GpuMeshBackend old,
                                              LinkedHashMap<BufferKey, VertexBuffer> oldResident) {
        if (generation < 0L || generation != lifecycleGeneration || generation != pendingReleaseGeneration
                || old != pendingReleaseBackend || oldResident != pendingReleaseResident) return;
        boolean context = hasCurrentContext();
        // Context loss can be temporary; keep the captured resources for the next render-thread retry.
        if (!context) return;
        pendingReleaseBackend = null;
        pendingReleaseResident = null;
        pendingReleaseGeneration = -1L;
        if (installed == old) {
            GpuInstanceBatch.installBackend(null);
            installed = null;
        }
        failed = false;
        disposeRequested = false;
        if (oldResident != null) clearResident(oldResident);
    }

    private static void clearResident(LinkedHashMap<BufferKey, VertexBuffer> resident) {
        for (VertexBuffer buffer : resident.values()) {
            try { buffer.close(); } catch (RuntimeException ignored) { }
        }
        resident.clear();
    }

    private static boolean hasCurrentContext() {
        try { return RenderSystem.isOnRenderThread() && org.lwjgl.opengl.GL.getCapabilities().OpenGL30; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    @Override public boolean supports(Object layer, Object texture, GpuInstanceBatch.LayerContract contract) {
        if (!IVRConfig.gpuOptimization || disposeRequested || failed || !hasCurrentContext() || (contract != GpuInstanceBatch.LayerContract.TEXTURED_QUADS && contract != GpuInstanceBatch.LayerContract.OPAQUE_ENTITY_CUTOUT)
                || !(layer instanceof RenderType type) || !(texture instanceof ResourceLocation)) return false;
        return type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS
                && GpuInstanceBatch.isSupportedGpuLayer(layer, texture);
    }

    @Override public GpuInstanceBatch.DrawResult draw(GpuInstanceBatch.Group group) {
        if (!supports(group.layer(), group.texture(), group.contract()) || group.instances().isEmpty()
                || group.mesh().vertices().isEmpty() || group.mesh().vertices().size() % 4 != 0
                || group.instances().size() > 256
                || (long) group.mesh().vertices().size() * group.instances().size() > 131072L) {
            return GpuInstanceBatch.DrawResult.rejected();
        }

        List<DrawItem> items = new ArrayList<>(group.instances().size());
        for (GpuInstanceBatch.Instance instance : group.instances()) {
            VertexBuffer buffer = resident(group.meshKey(), group.mesh(), instance);
            if (buffer == null) return GpuInstanceBatch.DrawResult.rejected();
            items.add(new DrawItem(buffer, instance));
        }

        RenderType layer = (RenderType) group.layer();
        ShaderInstance previousShader = RenderSystem.getShader();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean setup = false;
        int drawnCount = 0;
        boolean drawAttempted = false;
        try {
            layer.setupRenderState();
            setup = true;
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return GpuInstanceBatch.DrawResult.rejected();
            Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            for (DrawItem item : items) {
                item.buffer.bind();
                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
                modelView.multiply((Matrix4f) item.instance.poseMatrix());
                drawAttempted = true;
                item.buffer.drawWithShader(modelView, projection, shader);
                drawnCount++;
                VertexBuffer.unbind();
            }
            return GpuInstanceBatch.DrawResult.drawn(drawnCount);
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            GpuInstanceBatch.disableBackend();
            return drawAttempted ? GpuInstanceBatch.DrawResult.uncertain(drawnCount) : GpuInstanceBatch.DrawResult.rejected();
        } finally {
            try { VertexBuffer.unbind(); }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            if (setup) {
                try { layer.clearRenderState(); }
                catch (RuntimeException | LinkageError ignored) { failed = true; }
            }
            try { RenderSystem.setShader(() -> previousShader); }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            try { GL20.glUseProgram(previousProgram); }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            try { GL30.glBindVertexArray(previousVao); }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            try { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray); }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            if (failed) GpuInstanceBatch.disableBackend();
        }
    }

    private static VertexBuffer resident(VertexMeshKey meshKey, VertexMesh mesh, GpuInstanceBatch.Instance instance) {
        trimResidentToBudget();
        BufferKey key = new BufferKey(meshKey, mesh, instance.packedLight(), instance.packedOverlay(), instance.color());
        VertexBuffer cached = RESIDENT.get(key);
        if (cached != null) return cached;

        long bytes = (long) mesh.vertices().size() * 36L;
        long residentLimit = Math.min(MAX_RESIDENT_BYTES, IVRMemoryBudget.gpuMeshBudgetBytes());
        if (bytes <= 0 || bytes > residentLimit) return null;
        removeObsolete(meshKey, key);
        while (!RESIDENT.isEmpty() && (RESIDENT.size() >= MAX_RESIDENT_BUFFERS || residentBytes + bytes > residentLimit)) {
            Iterator<Map.Entry<BufferKey, VertexBuffer>> iterator = RESIDENT.entrySet().iterator();
            Map.Entry<BufferKey, VertexBuffer> eldest = iterator.next();
            iterator.remove();
            residentBytes -= bufferBytes(eldest.getKey());
            try { eldest.getValue().close(); } catch (RuntimeException ignored) { }
        }

        BufferBuilder builder = new BufferBuilder(Math.max(256, mesh.vertices().size() * 36));
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        emit(builder, mesh, instance);
        VertexBuffer buffer = new VertexBuffer();
        try {
            buffer.upload(builder.end());
            net.hulan.ivr.render_promotion.debug.RenderDebugStats.recordGpuUpload();
            RESIDENT.put(key, buffer);
            residentBytes += bytes;
            return buffer;
        } catch (RuntimeException | LinkageError failure) {
            try { buffer.close(); } catch (RuntimeException ignored) { }
            return null;
        } finally {
            try { VertexBuffer.unbind(); }
            catch (RuntimeException | LinkageError ignored) {
                failed = true;
                GpuInstanceBatch.disableBackend();
            }
        }
    }

    private static void trimResidentToBudget() {
        long residentLimit = Math.min(MAX_RESIDENT_BYTES, IVRMemoryBudget.gpuMeshBudgetBytes());
        while (!RESIDENT.isEmpty() && (RESIDENT.size() > MAX_RESIDENT_BUFFERS || residentBytes > residentLimit)) {
            Iterator<Map.Entry<BufferKey, VertexBuffer>> iterator = RESIDENT.entrySet().iterator();
            Map.Entry<BufferKey, VertexBuffer> eldest = iterator.next();
            iterator.remove();
            residentBytes -= bufferBytes(eldest.getKey());
            try { eldest.getValue().close(); } catch (RuntimeException ignored) { }
        }
    }

    private static void removeObsolete(VertexMeshKey meshKey, BufferKey keep) {
        Iterator<Map.Entry<BufferKey, VertexBuffer>> iterator = RESIDENT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BufferKey, VertexBuffer> entry = iterator.next();
            BufferKey old = entry.getKey();
            if (old.meshKey().equals(meshKey) && old.mesh() != keep.mesh()) {
                iterator.remove();
                residentBytes -= bufferBytes(old);
                try { entry.getValue().close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static long bufferBytes(BufferKey key) { return (long) key.mesh().vertices().size() * 36L; }

    private static void emit(BufferBuilder builder, VertexMesh mesh, GpuInstanceBatch.Instance instance) {
        for (VertexMesh.Vertex vertex : mesh.vertices()) {
            int color = vertex.color();
            int modulator = instance.color();
            builder.vertex(vertex.x(), vertex.y(), vertex.z())
                    .color((color >>> 16 & 255) * (modulator >>> 16 & 255) / 255,
                            (color >>> 8 & 255) * (modulator >>> 8 & 255) / 255,
                            (color & 255) * (modulator & 255) / 255,
                            (color >>> 24 & 255) * (modulator >>> 24 & 255) / 255)
                    .uv(vertex.u(), vertex.v()).overlayCoords(instance.packedOverlay()).uv2(instance.packedLight())
                    .normal(vertex.nx(), vertex.ny(), vertex.nz()).endVertex();
        }
    }

    private record BufferKey(VertexMeshKey meshKey, VertexMesh mesh, int light, int overlay, int color) { }
    private record DrawItem(VertexBuffer buffer, GpuInstanceBatch.Instance instance) { }
}
