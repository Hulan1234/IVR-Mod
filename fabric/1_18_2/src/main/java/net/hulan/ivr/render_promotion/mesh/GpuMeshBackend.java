package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minecraft-managed entity-cutout shader plus a raw OpenGL instanced draw path. */
public final class GpuMeshBackend implements GpuInstanceBatch.Backend {
    private static final int VERTEX_STRIDE = 48;
    private static final int INSTANCE_STRIDE = 76;
    private static final long MAX_RESIDENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RESIDENT_MESHES = 256;
    /** Explicit opt-in only; CPU rendering remains the default for every user. */
    private static final boolean INSTANCED_PATH_ENABLED = "true".equalsIgnoreCase(System.getProperty("ivr.gpuDoorInstances", "false"));
    private static final String SHADER_NAME = "ivr:instanced_entity_cutout";
    private static final String[] SHADER_MOD_CLASSES = {
            "net.irisshaders.iris.Iris", "net.coderbot.iris.Iris", "net.optifine.Config",
            "me.jellysquid.mods.sodium.client.SodiumClientMod",
            "net.caffeinemc.mods.sodium.client.SodiumClientMod",
            "org.embeddedt.embeddium.Embeddium"
    };

    /*
     * Instance attributes are configured directly on the raw OpenGL VAO below.
     * VertexFormatElement rejects repeated FLOAT/SHORT types in 1.18.2, so a
     * synthetic format containing four matrix rows cannot be constructed and
     * would crash class initialization even when the optional GPU path is off.
     * NEW_ENTITY supplies the vanilla per-vertex contract; the instance
     * attributes are looked up from the shader and installed explicitly.
     */
    private static final VertexFormat INSTANCE_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    private static GpuMeshBackend installed;
    private static boolean permanentlyDisabled;
    private static volatile boolean disposeRequested;
    private final ShaderInstance shader;
    private final LinkedHashMap<VertexMeshKey, MeshResource> meshes = new LinkedHashMap<>(16, .75F, true);
    private ByteBuffer instanceStaging = ByteBuffer.allocateDirect(16 * INSTANCE_STRIDE).order(ByteOrder.nativeOrder());
    private long residentBytes;
    private boolean disposed;

    private GpuMeshBackend() throws IOException {
        shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), SHADER_NAME, INSTANCE_FORMAT);
        for (String attribute : new String[]{"Position", "Color", "UV0", "Normal", "InstanceModel0", "InstanceModel1", "InstanceModel2", "InstanceModel3", "InstanceLight", "InstanceOverlay", "InstanceColor"}) requireAttribute(attribute);
        for (String uniform : new String[]{"ModelViewMat", "ProjMat", "IViewRotMat", "ColorModulator", "FogStart", "FogEnd", "FogColor", "FogShape"}) requireUniform(uniform);
    }

    public static void installIfSupported() {
        if (disposeRequested && hasCurrentContext()) releaseOnRenderThread();
        if (!INSTANCED_PATH_ENABLED || disposeRequested || !hasCurrentContext() || !RenderSystem.isOnRenderThread()
                || installed != null || permanentlyDisabled) return;
        try {
            if (!supportedRuntime()) { permanentlyDisabled = true; return; }
            GpuMeshBackend candidate = new GpuMeshBackend();
            try {
                GlSnapshot stateProbe = GlSnapshot.capture();
                if (!stateProbe.canRestoreSafely()) throw new IllegalStateException("Unsupported pre-existing OpenGL state");
                stateProbe.restore();
                installed = candidate;
                GpuInstanceBatch.installBackend(candidate);
            } catch (Throwable unusableState) {
                candidate.close();
                permanentlyDisabled = true;
                GpuInstanceBatch.installBackend(null);
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            permanentlyDisabled = true;
            GpuInstanceBatch.installBackend(null);
        }
    }

    public static void dispose() {
        disposeRequested = true;
        GpuInstanceBatch.disableBackend();
        Runnable release = GpuMeshBackend::releaseOnRenderThread;
        if (hasCurrentContext()) release.run();
        else RenderSystem.recordRenderCall(release::run);
    }

    private static void releaseOnRenderThread() {
        boolean context = hasCurrentContext();
        if (context) GpuInstanceBatch.installBackend(null);
        else GpuInstanceBatch.disableBackend();
        GpuMeshBackend old = installed;
        installed = null;
        permanentlyDisabled = false;
        disposeRequested = false;
        if (old != null) {
            if (context) old.close();
            else old.abandon();
        }
    }

    private static boolean hasCurrentContext() {
        try {
            return RenderSystem.isOnRenderThread() && GL.getCapabilities().OpenGL33;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean supportedRuntime() {
        try {
            if (!GL.getCapabilities().OpenGL33 || GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS) < 13) return false;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
        ClassLoader loader = GpuMeshBackend.class.getClassLoader();
        for (String name : SHADER_MOD_CLASSES) {
            try { Class.forName(name, false, loader); return false; }
            catch (ClassNotFoundException ignored) { }
            catch (LinkageError ignored) { return false; }
        }
        return true;
    }

    @Override
    public boolean supports(Object layer, Object texture, GpuInstanceBatch.LayerContract contract) {
        if (!INSTANCED_PATH_ENABLED || disposeRequested || !hasCurrentContext() || disposed || contract != GpuInstanceBatch.LayerContract.OPAQUE_ENTITY_CUTOUT || !(layer instanceof RenderType) || !(texture instanceof ResourceLocation)) return false;
        return GpuInstanceBatch.isSupportedOpaqueLayer(layer, texture);
    }

    @Override
    public GpuInstanceBatch.DrawResult draw(GpuInstanceBatch.Group group) {
        if (!INSTANCED_PATH_ENABLED) return GpuInstanceBatch.DrawResult.rejected();
        boolean drawStarted = false;
        RenderType layer = (RenderType) group.layer();
        GlSnapshot state = null;
        boolean setup = false;
        try {
            if (!supports(group.layer(), group.texture(), group.contract()) || group.instances().isEmpty()) return GpuInstanceBatch.DrawResult.rejected();
            state = GlSnapshot.capture();
            if (!state.canRestoreSafely()) {
                // Capture only probes state and restores the active texture itself.
                // Do not run the full restore sequence when state is outside our safe contract.
                state = null;
                return GpuInstanceBatch.DrawResult.rejected();
            }
            setup = true;
            layer.setupRenderState();
            MeshResource mesh = resident(group.meshKey(), group.mesh());
            if (mesh == null || mesh.indexCount == 0) return GpuInstanceBatch.DrawResult.rejected();
            uploadInstances(mesh, group.instances());
            updateShaderUniforms((ResourceLocation) group.texture());
            RenderSystem.setShader(() -> shader);
            shader.apply();
            GlStateManager._glBindVertexArray(mesh.vao);
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, mesh.indexCount, GL11.GL_UNSIGNED_INT, 0L, group.instances().size());
            drawStarted = true;
            return GpuInstanceBatch.DrawResult.drawn(group.instances().size());
        } catch (Throwable failure) {
            return drawStarted ? GpuInstanceBatch.DrawResult.uncertain() : GpuInstanceBatch.DrawResult.rejected();
        } finally {
            if (setup) try { layer.clearRenderState(); } catch (Throwable ignored) { }
            if (state != null) try { state.restore(); } catch (Throwable ignored) {
                permanentlyDisabled = true;
                disposed = true;
                GpuInstanceBatch.installBackend(null);
            }
        }
    }

    private void updateShaderUniforms(ResourceLocation texture) {
        requireUniform("ModelViewMat").set(RenderSystem.getModelViewMatrix());
        requireUniform("ProjMat").set(RenderSystem.getProjectionMatrix());
        requireUniform("IViewRotMat").set(RenderSystem.getInverseViewRotationMatrix());
        requireUniform("ColorModulator").set(RenderSystem.getShaderColor());
        requireUniform("FogStart").set(RenderSystem.getShaderFogStart());
        requireUniform("FogEnd").set(RenderSystem.getShaderFogEnd());
        requireUniform("FogColor").set(RenderSystem.getShaderFogColor());
        requireUniform("FogShape").set(RenderSystem.getShaderFogShape().getIndex());
        RenderSystem.setupShaderLights(shader);
        RenderSystem.setShaderTexture(0, texture);
        shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
        shader.setSampler("Sampler1", RenderSystem.getShaderTexture(1));
        shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
    }

    private Uniform requireUniform(String name) {
        Uniform result = shader.getUniform(name);
        if (result == null) throw new IllegalStateException("Missing instanced shader uniform " + name);
        return result;
    }

    private void requireAttribute(String name) {
        if (GL20.glGetAttribLocation(shader.getId(), name) < 0) throw new IllegalStateException("Missing instanced shader attribute " + name);
    }

    private MeshResource resident(VertexMeshKey key, VertexMesh mesh) {
        MeshResource old = meshes.get(key);
        if (old != null && old.source == mesh) return old;
        if (old != null) { meshes.remove(key); residentBytes -= old.bytes; old.close(); }
        long bytes = mesh.byteSize();
        if (bytes <= 0 || bytes > MAX_RESIDENT_BYTES || mesh.vertices().size() < 4 || mesh.vertices().size() % 4 != 0) return null;
        while (!meshes.isEmpty() && (meshes.size() >= MAX_RESIDENT_MESHES || residentBytes + bytes > MAX_RESIDENT_BYTES)) {
            Iterator<Map.Entry<VertexMeshKey, MeshResource>> it = meshes.entrySet().iterator();
            Map.Entry<VertexMeshKey, MeshResource> eldest = it.next(); it.remove(); residentBytes -= eldest.getValue().bytes; eldest.getValue().close();
        }
        MeshResource result = MeshResource.upload(mesh, shader);
        if (result == null) return null;
        meshes.put(key, result); residentBytes += result.bytes; return result;
    }

    private void uploadInstances(MeshResource mesh, java.util.List<GpuInstanceBatch.Instance> instances) {
        int required = instances.size() * INSTANCE_STRIDE;
        if (instanceStaging.capacity() < required) instanceStaging = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder());
        instanceStaging.clear();
        for (GpuInstanceBatch.Instance instance : instances) {
            for (float value : instance.matrix()) instanceStaging.putFloat(value);
            int light = instance.packedLight(), overlay = instance.packedOverlay(), color = instance.color();
            instanceStaging.putShort((short) light).putShort((short) (light >>> 16));
            instanceStaging.putShort((short) overlay).putShort((short) (overlay >>> 16));
            instanceStaging.put((byte) (color >>> 16)).put((byte) (color >>> 8)).put((byte) color).put((byte) (color >>> 24));
        }
        instanceStaging.flip();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.instanceBuffer);
        GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, instanceStaging, GL15.GL_STREAM_DRAW);
    }

    private void close() {
        disposed = true;
        for (MeshResource mesh : meshes.values()) mesh.close();
        meshes.clear(); residentBytes = 0L;
        try { shader.close(); } catch (RuntimeException ignored) { }
    }

    /** The context has already been destroyed; its GL objects were destroyed with it. */
    private void abandon() {
        disposed = true;
        meshes.clear();
        residentBytes = 0L;
    }

    private static int attr(String name, ShaderInstance shader) { int location = GL20.glGetAttribLocation(shader.getId(), name); if (location < 0) throw new IllegalStateException("Inactive shader attribute " + name); return location; }
    private static void vertexAttr(int location, int size, int type, boolean normalized, int stride, long offset) { GlStateManager._enableVertexAttribArray(location); GlStateManager._vertexAttribPointer(location, size, type, normalized, stride, offset); GL33.glVertexAttribDivisor(location, 0); }
    private static void instanceAttr(int location, int size, int type, int stride, long offset) { GlStateManager._enableVertexAttribArray(location); GlStateManager._vertexAttribIPointer(location, size, type, stride, offset); GL33.glVertexAttribDivisor(location, 1); }

    private static final class MeshResource {
        final VertexMesh source; final long bytes; final int vao, vertexBuffer, indexBuffer, instanceBuffer, indexCount;
        MeshResource(VertexMesh source, int vao, int vertexBuffer, int indexBuffer, int instanceBuffer, int indexCount) { this.source = source; this.bytes = source.byteSize(); this.vao = vao; this.vertexBuffer = vertexBuffer; this.indexBuffer = indexBuffer; this.instanceBuffer = instanceBuffer; this.indexCount = indexCount; }
        static MeshResource upload(VertexMesh mesh, ShaderInstance shader) {
            int vao = 0, vertex = 0, index = 0, instances = 0; int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                int count = mesh.vertices().size();
                ByteBuffer vertices = ByteBuffer.allocateDirect(count * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
                for (VertexMesh.Vertex v : mesh.vertices()) { int c = v.color(); vertices.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat((c >>> 16 & 255) / 255F).putFloat((c >>> 8 & 255) / 255F).putFloat((c & 255) / 255F).putFloat((c >>> 24 & 255) / 255F).putFloat(v.u()).putFloat(v.v()).putFloat(v.nx()).putFloat(v.ny()).putFloat(v.nz()); }
                vertices.flip(); int indexCount = count / 4 * 6; ByteBuffer indices = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder());
                for (int q = 0; q < count; q += 4) indices.putInt(q).putInt(q + 1).putInt(q + 2).putInt(q + 2).putInt(q + 3).putInt(q); indices.flip();
                vao = GlStateManager._glGenVertexArrays(); vertex = GlStateManager._glGenBuffers(); index = GlStateManager._glGenBuffers(); instances = GlStateManager._glGenBuffers(); GlStateManager._glBindVertexArray(vao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertex); GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW); vertexAttr(attr("Position", shader), 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0); vertexAttr(attr("Color", shader), 4, GL11.GL_FLOAT, false, VERTEX_STRIDE, 12); vertexAttr(attr("UV0", shader), 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 28); vertexAttr(attr("Normal", shader), 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 36);
                GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, index); GlStateManager._glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, instances); int[] m = {attr("InstanceModel0", shader), attr("InstanceModel1", shader), attr("InstanceModel2", shader), attr("InstanceModel3", shader)}; for (int i = 0; i < 4; i++) { vertexAttr(m[i], 4, GL11.GL_FLOAT, false, INSTANCE_STRIDE, i * 16L); GL33.glVertexAttribDivisor(m[i], 1); } instanceAttr(attr("InstanceLight", shader), 2, GL11.GL_SHORT, INSTANCE_STRIDE, 64); instanceAttr(attr("InstanceOverlay", shader), 2, GL11.GL_SHORT, INSTANCE_STRIDE, 68); vertexAttr(attr("InstanceColor", shader), 4, GL11.GL_UNSIGNED_BYTE, true, INSTANCE_STRIDE, 72); GL33.glVertexAttribDivisor(attr("InstanceColor", shader), 1);
                return new MeshResource(mesh, vao, vertex, index, instances, indexCount);
            } catch (Throwable failure) { delete(vertex, index, instances, vao); return null; }
            finally { GlStateManager._glBindVertexArray(oldVao); GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray); }
        }
        void close() { delete(vertexBuffer, indexBuffer, instanceBuffer, vao); }
        static void delete(int vertex, int index, int instances, int vao) { if (vertex != 0) GlStateManager._glDeleteBuffers(vertex); if (index != 0) GlStateManager._glDeleteBuffers(index); if (instances != 0) GlStateManager._glDeleteBuffers(instances); if (vao != 0) GlStateManager._glDeleteVertexArrays(vao); }
    }

    /** Captures the state touched by vanilla RenderType setup/clear and ShaderInstance.apply. */
    private static final class GlSnapshot {
        // LWJGL 3.2.2 exposes glBlendColor but omits this OpenGL 1.4 query enum.
        private static final int GL_BLEND_COLOR = 0x8005;
        final ShaderInstance shader = RenderSystem.getShader();
        final int program = integer(GL20.GL_CURRENT_PROGRAM);
        final int vao = integer(GL30.GL_VERTEX_ARRAY_BINDING), array = integer(GL15.GL_ARRAY_BUFFER_BINDING);
        final int active = integer(GL13.GL_ACTIVE_TEXTURE);
        final int drawFramebuffer = integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFramebuffer = integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int[] shaderTextures = new int[3], textures, samplers;
        final int[] viewport = new int[4], scissorBox = new int[4], polygonMode = new int[2];
        final boolean blend = enabled(GL11.GL_BLEND), cull = enabled(GL11.GL_CULL_FACE);
        final boolean depth = enabled(GL11.GL_DEPTH_TEST), scissor = enabled(GL11.GL_SCISSOR_TEST);
        final boolean stencil = enabled(GL11.GL_STENCIL_TEST), polygonOffset = enabled(GL11.GL_POLYGON_OFFSET_FILL);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int depthFunc = integer(GL11.GL_DEPTH_FUNC);
        final int srcRgb = integer(GL14.GL_BLEND_SRC_RGB), dstRgb = integer(GL14.GL_BLEND_DST_RGB);
        final int srcAlpha = integer(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = integer(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = integer(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = integer(GL20.GL_BLEND_EQUATION_ALPHA);
        final int cullFace = integer(GL11.GL_CULL_FACE_MODE), frontFace = integer(GL11.GL_FRONT_FACE);
        final float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        final float offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        final float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        final float[] blendColor = new float[4];
        final boolean[] colorMask = new boolean[4];
        final int[] stencilFront = stencilState(false), stencilBack = stencilState(true);

        GlSnapshot() {
            for (int i = 0; i < shaderTextures.length; i++) shaderTextures[i] = RenderSystem.getShaderTexture(i);
            textures = new int[integer(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)];
            samplers = new int[textures.length];
            try {
                for (int i = 0; i < textures.length; i++) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                    textures[i] = integer(GL11.GL_TEXTURE_BINDING_2D);
                    samplers[i] = integer(GL33.GL_SAMPLER_BINDING);
                }
            } finally { RenderSystem.activeTexture(active); }
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygonMode);
            GL11.glGetFloatv(GL_BLEND_COLOR, blendColor);
            ByteBuffer mask = ByteBuffer.allocateDirect(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
        }

        static int integer(int name) { return GL11.glGetInteger(name); }
        static boolean enabled(int name) { return GL11.glIsEnabled(name); }
        static GlSnapshot capture() { return new GlSnapshot(); }
        boolean canRestoreSafely() {
            return equationRgb == equationAlpha
                    && stencilFrontEqualsBack()
                    && cullFace == GL11.GL_BACK && frontFace == GL11.GL_CCW
                    && polygonMode[0] == GL11.GL_FILL && polygonMode[1] == GL11.GL_FILL;
        }
        boolean stencilFrontEqualsBack() {
            for (int i = 0; i < stencilFront.length; i++) if (stencilFront[i] != stencilBack[i]) return false;
            return true;
        }
        static int[] stencilState(boolean back) {
            return back ? new int[]{integer(GL20.GL_STENCIL_BACK_FUNC), integer(GL20.GL_STENCIL_BACK_REF),
                    integer(GL20.GL_STENCIL_BACK_VALUE_MASK), integer(GL20.GL_STENCIL_BACK_WRITEMASK),
                    integer(GL20.GL_STENCIL_BACK_FAIL), integer(GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL), integer(GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS)}
                    : new int[]{integer(GL11.GL_STENCIL_FUNC), integer(GL11.GL_STENCIL_REF),
                    integer(GL11.GL_STENCIL_VALUE_MASK), integer(GL11.GL_STENCIL_WRITEMASK),
                    integer(GL11.GL_STENCIL_FAIL), integer(GL11.GL_STENCIL_PASS_DEPTH_FAIL), integer(GL11.GL_STENCIL_PASS_DEPTH_PASS)};
        }
        static void restoreStencil(int face, int[] values) {
            GL20.glStencilFuncSeparate(face, values[0], values[1], values[2]);
            GL20.glStencilMaskSeparate(face, values[3]);
            GL20.glStencilOpSeparate(face, values[4], values[5], values[6]);
        }
        static void rawEnable(int capability, boolean value) {
            if (value) GL11.glEnable(capability); else GL11.glDisable(capability);
        }
        void restore() {
            // Restore both the RenderSystem reference and the actual program; setShader only changes the reference.
            if (shader != null) RenderSystem.setShader(() -> shader);
            else GlStateManager._glUseProgram(program);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(depthMask);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.blendEquation(equationRgb);
            GL14.glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3]);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GlStateManager._scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            if (scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
            rawEnable(GL11.GL_STENCIL_TEST, stencil);
            restoreStencil(GL11.GL_FRONT, stencilFront);
            restoreStencil(GL11.GL_BACK, stencilBack);
            RenderSystem.polygonOffset(offsetFactor, offsetUnits);
            if (polygonOffset) RenderSystem.enablePolygonOffset(); else RenderSystem.disablePolygonOffset();
            GL11.glCullFace(cullFace);
            GL11.glFrontFace(frontFace);
            GL11.glPolygonMode(GL11.GL_FRONT, polygonMode[0]);
            GL11.glPolygonMode(GL11.GL_BACK, polygonMode[1]);
            RenderSystem.lineWidth(lineWidth);
            for (int i = 0; i < shaderTextures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textures[i]);
                GL33.glBindSampler(i, samplers[i]);
            }
            RenderSystem.activeTexture(active);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
        }
    }
}
