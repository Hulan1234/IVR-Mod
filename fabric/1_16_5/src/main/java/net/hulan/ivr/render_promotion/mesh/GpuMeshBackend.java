package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Matrix4f;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional cached VBO draw restricted to MTR's opaque entity cutout layer. */
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
        if (context()) {
            for (VertexBuffer buffer : RESIDENT.values()) {
                try { buffer.close(); } catch (RuntimeException ignored) { }
            }
        }
        RESIDENT.clear();
        residentBytes = 0L;
    }

    private static boolean context() {
        try { return RenderSystem.isOnRenderThread() && GL.getCapabilities().OpenGL20; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    @Override public boolean supports(Object layer, Object texture, GpuInstanceBatch.LayerContract contract) {
        if (!ENABLED || failed || !context() || contract != GpuInstanceBatch.LayerContract.OPAQUE_ENTITY_CUTOUT
                || !(layer instanceof RenderType) || !(texture instanceof ResourceLocation)) return false;
        RenderType type = (RenderType) layer;
        return type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == 7
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
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        RenderSystem.activeTexture(activeTexture);
        boolean stateSetup = false;
        int drawnCount = 0;
        boolean drawAttempted = false;
        try {
            layer.setupRenderState();
            stateSetup = true;
            for (DrawItem item : items) {
                item.buffer.bind();
                DefaultVertexFormat.NEW_ENTITY.setupBufferState(0L);

                // The second argument is the primitive mode (GL_QUADS), not a packed vertex attribute.
                drawAttempted = true;
                item.buffer.draw((Matrix4f) item.instance.poseMatrix(), 7);
                drawnCount++;
                DefaultVertexFormat.NEW_ENTITY.clearBufferState();
                VertexBuffer.unbind();
            }
            return GpuInstanceBatch.DrawResult.drawn(drawnCount);
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            GpuInstanceBatch.disableBackend();
            return drawAttempted ? GpuInstanceBatch.DrawResult.uncertain() : GpuInstanceBatch.DrawResult.rejected();
        } finally {
            try { DefaultVertexFormat.NEW_ENTITY.clearBufferState(); } catch (RuntimeException ignored) { failed = true; }
            VertexBuffer.unbind();
            if (stateSetup) try { layer.clearRenderState(); } catch (RuntimeException ignored) { failed = true; }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(oldTexture);
            RenderSystem.activeTexture(activeTexture);
            GlStateManager._glUseProgram(oldProgram);
            RenderSystem.glBindBuffer(GL15.GL_ARRAY_BUFFER, () -> oldArray);
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
        builder.begin(7, DefaultVertexFormat.NEW_ENTITY);
        emit(builder, mesh, instance);
        builder.end();
        VertexBuffer buffer = new VertexBuffer(DefaultVertexFormat.NEW_ENTITY);
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

    private static long bufferBytes(BufferKey key) { return (long) key.mesh().vertices().size() * 36L; }
    private record BufferKey(VertexMeshKey meshKey, VertexMesh mesh, int light, int overlay, int color) { }
    private record DrawItem(VertexBuffer buffer, GpuInstanceBatch.Instance instance) { }
}
