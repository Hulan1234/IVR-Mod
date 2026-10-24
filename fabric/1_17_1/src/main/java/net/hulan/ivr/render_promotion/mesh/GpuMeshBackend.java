package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
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

/** Optional cached VBO draw of one exact opaque entity layer. */
public final class GpuMeshBackend implements GpuInstanceBatch.Backend {
    /** Legacy VertexBuffer state restoration is not audited on this mapping. */
    private static final boolean ENABLED = "true".equalsIgnoreCase(System.getProperty("ivr.gpuDoorInstances", "false"))
            && "true".equalsIgnoreCase(System.getProperty("ivr.gpuLegacyStateContract", "false"));
    private static final long MAX_RESIDENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RESIDENT_BUFFERS = 256;
    private static final LinkedHashMap<BufferKey, VertexBuffer> RESIDENT = new LinkedHashMap<>(16, .75F, true);
    private static GpuMeshBackend installed;
    private static boolean failed;
    private static long residentBytes;

    private GpuMeshBackend() { }

    public static void installIfSupported() {
        if (!ENABLED || installed != null || failed || !context()) return;
        try {
            for (String name : new String[]{"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris", "net.optifine.Config",
                    "me.jellysquid.mods.sodium.client.SodiumClientMod", "net.caffeinemc.mods.sodium.client.SodiumClientMod", "org.embeddedt.embeddium.Embeddium"}) {
                try { Class.forName(name, false, GpuMeshBackend.class.getClassLoader()); failed = true; return; }
                catch (ClassNotFoundException ignored) { }
            }
            installed = new GpuMeshBackend();
            GpuInstanceBatch.installBackend(installed);
        } catch (RuntimeException | LinkageError unavailable) { failed = true; }
    }

    public static void dispose() {
        GpuInstanceBatch.disableBackend();
        installed = null;
        failed = false;
        if (RenderSystem.isOnRenderThreadOrInit()) clearResident();
        else RenderSystem.recordRenderCall(GpuMeshBackend::clearResident);
    }

    private static void clearResident() {
        for (VertexBuffer buffer : RESIDENT.values()) {
            try { buffer.close(); } catch (RuntimeException ignored) { }
        }
        RESIDENT.clear();
        residentBytes = 0L;
    }

    private static boolean context() {
        try { return RenderSystem.isOnRenderThread() && org.lwjgl.opengl.GL.getCapabilities().OpenGL30; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    @Override public boolean supports(Object layer, Object texture, GpuInstanceBatch.LayerContract contract) {
        if (!ENABLED || failed || !context() || contract != GpuInstanceBatch.LayerContract.OPAQUE_ENTITY_CUTOUT
                || !(layer instanceof RenderType) || !(texture instanceof ResourceLocation)) return false;
        RenderType type = (RenderType) layer;
        return type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS
                && GpuInstanceBatch.isSupportedOpaqueLayer(layer, texture);
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
            return drawAttempted ? GpuInstanceBatch.DrawResult.uncertain() : GpuInstanceBatch.DrawResult.rejected();
        } finally {
            VertexBuffer.unbind();
            if (setup) try { layer.clearRenderState(); } catch (RuntimeException ignored) { failed = true; }
            RenderSystem.setShader(() -> previousShader);
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
        }
    }

    private static VertexBuffer resident(VertexMeshKey meshKey, VertexMesh mesh, GpuInstanceBatch.Instance instance) {
        BufferKey key = new BufferKey(meshKey, mesh, instance.packedLight(), instance.packedOverlay(), instance.color());
        VertexBuffer cached = RESIDENT.get(key);
        if (cached != null) return cached;

        long bytes = (long) mesh.vertices().size() * 36L;
        if (bytes <= 0 || bytes > MAX_RESIDENT_BYTES) return null;
        removeObsolete(meshKey, key);
        while (!RESIDENT.isEmpty() && (RESIDENT.size() >= MAX_RESIDENT_BUFFERS || residentBytes + bytes > MAX_RESIDENT_BYTES)) {
            Iterator<Map.Entry<BufferKey, VertexBuffer>> iterator = RESIDENT.entrySet().iterator();
            Map.Entry<BufferKey, VertexBuffer> eldest = iterator.next();
            iterator.remove();
            residentBytes -= bufferBytes(eldest.getKey());
            try { eldest.getValue().close(); } catch (RuntimeException ignored) { }
        }

        BufferBuilder builder = new BufferBuilder(Math.max(256, mesh.vertices().size() * 36));
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        emit(builder, mesh, instance);
        builder.end();
        VertexBuffer buffer = new VertexBuffer();
        try {
            buffer.upload(builder);
            RESIDENT.put(key, buffer);
            residentBytes += bytes;
            return buffer;
        } catch (RuntimeException | LinkageError failure) {
            try { buffer.close(); } catch (RuntimeException ignored) { }
            return null;
        } finally {
            VertexBuffer.unbind();
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
