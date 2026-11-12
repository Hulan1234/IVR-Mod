package net.hulan.ivr.render_promotion.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.DependenciesChecker;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import net.hulan.ivr.client.IVRConfig;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** 对单个确定的不透明实体图层执行可选的 VBO 缓存绘制。 */
public final class GpuMeshBackend implements GpuInstanceBatch.Backend {
    /** Minecraft 1.19.4 RenderSystem exposes twelve shader texture slots. */
    private static final int SHADER_TEXTURE_COUNT = 12;
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

    private GpuMeshBackend() {
    }

    public static void installIfSupported() {
        if (disposeRequested && hasCurrentContext()) {
            releaseOnRenderThread(pendingReleaseGeneration, pendingReleaseBackend, pendingReleaseResident);
        }
        if (!IVRConfig.gpuOptimization || disposeRequested || installed != null || failed || !hasCurrentContext()) return;
        try {
            if (DependenciesChecker.isGpuMeshBackendBlocked()) {
                failed = true;
                return;
            }
            installed = new GpuMeshBackend();
            lifecycleGeneration++;
            GpuInstanceBatch.installBackend(installed);
        } catch (RuntimeException | LinkageError unavailable) {
            failed = true;
        }
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
            try {
                buffer.close();
            } catch (RuntimeException ignored) {
            }
        }
        resident.clear();
    }

    private static boolean hasCurrentContext() {
        try {
            return RenderSystem.isOnRenderThread() && org.lwjgl.opengl.GL.getCapabilities().OpenGL30;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    @Override
    public boolean supports(Object layer, Object texture, GpuInstanceBatch.LayerContract contract) {
        if (!IVRConfig.gpuOptimization || disposeRequested || failed || !hasCurrentContext()
                || (contract != GpuInstanceBatch.LayerContract.TEXTURED_QUADS
                && contract != GpuInstanceBatch.LayerContract.OPAQUE_ENTITY_CUTOUT)
                || !(layer instanceof RenderType type) || !(texture instanceof ResourceLocation)) {
            return false;
        }
        return type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS
                && GpuInstanceBatch.isSupportedGpuLayer(layer, texture);
    }

    @Override
    public GpuInstanceBatch.DrawResult draw(GpuInstanceBatch.Group group) {
        if (!supports(group.layer(), group.texture(), group.contract()) || group.instances().isEmpty()
                || group.mesh().vertices().isEmpty() || group.mesh().vertices().size() % 4 != 0
                || group.instances().size() > 256
                || (long) group.mesh().vertices().size() * group.instances().size() > 131072L) {
            return GpuInstanceBatch.DrawResult.rejected();
        }

        long residentLimit = Math.min(MAX_RESIDENT_BYTES, IVRMemoryBudget.gpuMeshBudgetBytes());
        long groupBytes = (long) group.mesh().vertices().size() * 36L * group.instances().size();
        if (groupBytes > residentLimit) {
            IVRMemoryBudget.recordReject("gpu");
            return GpuInstanceBatch.DrawResult.rejected();
        }

        GlStateSnapshot state;
        try {
            state = new GlStateSnapshot();
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            GpuInstanceBatch.disableBackend();
            return GpuInstanceBatch.DrawResult.rejected();
        }

        RenderType layer = (RenderType) group.layer();
        boolean setupAttempted = false;
        int drawnCount = 0;
        boolean drawAttempted = false;
        GpuInstanceBatch.DrawResult result = GpuInstanceBatch.DrawResult.rejected();
        try {
            List<DrawItem> items = new ArrayList<>(group.instances().size());
            boolean buffersReady = true;
            for (GpuInstanceBatch.Instance instance : group.instances()) {
                VertexBuffer buffer = resident(group.meshKey(), group.mesh(), instance);
                if (buffer == null) {
                    buffersReady = false;
                    break;
                }
                items.add(new DrawItem(buffer, instance));
            }

            if (buffersReady) {
                setupAttempted = true;
                layer.setupRenderState();
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
                    for (DrawItem item : items) {
                        item.buffer.bind();
                        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
                        modelView.mul((Matrix4f) item.instance.poseMatrix());
                        drawAttempted = true;
                        item.buffer.drawWithShader(modelView, projection, shader);
                        drawnCount++;
                        VertexBuffer.unbind();
                    }
                    result = GpuInstanceBatch.DrawResult.drawn(drawnCount);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            GpuInstanceBatch.disableBackend();
            result = drawAttempted
                    ? GpuInstanceBatch.DrawResult.uncertain(drawnCount)
                    : GpuInstanceBatch.DrawResult.rejected();
        } finally {
            try {
                VertexBuffer.unbind();
            } catch (RuntimeException | LinkageError failure) {
                failed = true;
            }
            if (setupAttempted) {
                try {
                    layer.clearRenderState();
                } catch (RuntimeException | LinkageError failure) {
                    failed = true;
                }
            }
            if (!state.restore()) {
                failed = true;
            }
            if (failed) {
                GpuInstanceBatch.disableBackend();
            }
        }
        return result;
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
            try {
                eldest.getValue().close();
            } catch (RuntimeException ignored) {
            }
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
            try {
                buffer.close();
            } catch (RuntimeException ignored) {
            }
            return null;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static void trimResidentToBudget() {
        long residentLimit = Math.min(MAX_RESIDENT_BYTES, IVRMemoryBudget.gpuMeshBudgetBytes());
        while (!RESIDENT.isEmpty() && (RESIDENT.size() > MAX_RESIDENT_BUFFERS || residentBytes > residentLimit)) {
            Iterator<Map.Entry<BufferKey, VertexBuffer>> iterator = RESIDENT.entrySet().iterator();
            Map.Entry<BufferKey, VertexBuffer> eldest = iterator.next();
            iterator.remove();
            residentBytes -= bufferBytes(eldest.getKey());
            try {
                eldest.getValue().close();
            } catch (RuntimeException ignored) {
            }
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
                try {
                    entry.getValue().close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static long bufferBytes(BufferKey key) {
        return (long) key.mesh().vertices().size() * 36L;
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

    private record BufferKey(VertexMeshKey meshKey, VertexMesh mesh, int light, int overlay, int color) {
    }

    private record DrawItem(VertexBuffer buffer, GpuInstanceBatch.Instance instance) {
    }

    private static final class GlStateSnapshot {
        private final ShaderInstance shader;
        private final int program;
        private final int vertexArray;
        private final int arrayBuffer;
        private final int drawFramebuffer;
        private final int readFramebuffer;
        private final int[] drawBuffers;
        private final int readBuffer;
        private final int activeTexture;
        private final int[] shaderTextures = new int[SHADER_TEXTURE_COUNT];
        private final int[] textureBindings = new int[SHADER_TEXTURE_COUNT];
        private final int[] viewport = new int[4];
        private final int[] scissorBox = new int[4];
        private final float[] blendColor = new float[4];
        private final float[] depthRange = new float[2];
        private final boolean[] colorMask = new boolean[4];
        private final boolean blendEnabled;
        private final int blendSourceRgb;
        private final int blendDestinationRgb;
        private final int blendSourceAlpha;
        private final int blendDestinationAlpha;
        private final int blendEquationRgb;
        private final int blendEquationAlpha;
        private final boolean depthTestEnabled;
        private final int depthFunction;
        private final boolean depthMask;
        private final boolean cullEnabled;
        private final int cullFace;
        private final int frontFace;
        private final boolean polygonOffsetEnabled;
        private final float polygonOffsetFactor;
        private final float polygonOffsetUnits;
        private final boolean scissorEnabled;
        private final boolean stencilEnabled;
        private final int stencilFunctionFront;
        private final int stencilReferenceFront;
        private final int stencilValueMaskFront;
        private final int stencilWriteMaskFront;
        private final int stencilFailFront;
        private final int stencilDepthFailFront;
        private final int stencilDepthPassFront;
        private final int stencilFunctionBack;
        private final int stencilReferenceBack;
        private final int stencilValueMaskBack;
        private final int stencilWriteMaskBack;
        private final int stencilFailBack;
        private final int stencilDepthFailBack;
        private final int stencilDepthPassBack;
        private final boolean colorLogicEnabled;
        private final int logicOperation;
        private final float lineWidth;

        private GlStateSnapshot() {
            shader = RenderSystem.getShader();
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int maxDrawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            drawBuffers = new int[maxDrawBuffers];
            for (int index = 0; index < drawBuffers.length; index++) {
                drawBuffers[index] = GL11.glGetInteger(GL30.GL_DRAW_BUFFER0 + index);
            }
            readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
            blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            polygonOffsetEnabled = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            polygonOffsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            polygonOffsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            stencilFunctionFront = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
            stencilReferenceFront = GL11.glGetInteger(GL11.GL_STENCIL_REF);
            stencilValueMaskFront = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
            stencilWriteMaskFront = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
            stencilFailFront = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
            stencilDepthFailFront = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            stencilDepthPassFront = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
            stencilFunctionBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC);
            stencilReferenceBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_REF);
            stencilValueMaskBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_VALUE_MASK);
            stencilWriteMaskBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK);
            stencilFailBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_FAIL);
            stencilDepthFailBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL);
            stencilDepthPassBack = GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS);
            colorLogicEnabled = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
            logicOperation = GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE);
            lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer integerValues = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, integerValues);
                copy(integerValues, viewport);
                integerValues.clear();
                GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, integerValues);
                copy(integerValues, scissorBox);

                FloatBuffer floatValues = stack.mallocFloat(4);
                GL11.glGetFloatv(ARBImaging.GL_BLEND_COLOR, floatValues);
                copy(floatValues, blendColor);
                floatValues.clear();
                GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, floatValues);
                copy(floatValues, depthRange);

                ByteBuffer booleanValues = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, booleanValues);
                for (int index = 0; index < colorMask.length; index++) {
                    colorMask[index] = booleanValues.get(index) != 0;
                }
            }

            try {
                for (int unit = 0; unit < SHADER_TEXTURE_COUNT; unit++) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                    shaderTextures[unit] = RenderSystem.getShaderTexture(unit);
                    textureBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                }
            } finally {
                RenderSystem.activeTexture(activeTexture);
            }
        }

        private boolean restore() {
            if (!hasCurrentContext()) {
                return false;
            }

            boolean restored = true;
            restored = attempt(() -> RenderSystem.setShader(() -> shader)) && restored;
            restored = attempt(() -> GL20.glUseProgram(program)) && restored;
            restored = restoreTextureBindings() && restored;

            restored = attempt(() -> {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                if (drawFramebuffer == 0) {
                    GL11.glDrawBuffer(drawBuffers[0]);
                } else {
                    int drawBufferCount = drawBuffers.length;
                    while (drawBufferCount > 1 && drawBuffers[drawBufferCount - 1] == GL11.GL_NONE) {
                        drawBufferCount--;
                    }
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer drawBufferValues = stack.mallocInt(drawBufferCount);
                        drawBufferValues.put(drawBuffers, 0, drawBufferCount).flip();
                        GL20.glDrawBuffers(drawBufferValues);
                    }
                }
                GL11.glReadBuffer(readBuffer);
            }) && restored;
            restored = attempt(() -> {
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            }) && restored;

            restored = attempt(() -> {
                if (blendEnabled) {
                    RenderSystem.enableBlend();
                } else {
                    RenderSystem.disableBlend();
                }
                RenderSystem.blendFuncSeparate(
                        blendSourceRgb, blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha);
            }) && restored;
            restored = attempt(() -> {
                if (blendEnabled) {
                    GL11.glEnable(GL11.GL_BLEND);
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                }
                GL14.glBlendFuncSeparate(
                        blendSourceRgb, blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha);
                GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
                GL14.glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3]);
            }) && restored;
            restored = attempt(() -> RenderSystem.blendEquation(blendEquationRgb)) && restored;
            restored = attempt(() -> GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)) && restored;

            restored = attempt(() -> {
                if (depthTestEnabled) {
                    RenderSystem.enableDepthTest();
                } else {
                    RenderSystem.disableDepthTest();
                }
                RenderSystem.depthFunc(depthFunction);
                RenderSystem.depthMask(depthMask);
            }) && restored;
            restored = attempt(() -> {
                if (depthTestEnabled) {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                } else {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                }
                GL11.glDepthFunc(depthFunction);
                GL11.glDepthMask(depthMask);
                GL11.glDepthRange(depthRange[0], depthRange[1]);
            }) && restored;

            restored = attempt(() -> {
                if (cullEnabled) {
                    RenderSystem.enableCull();
                } else {
                    RenderSystem.disableCull();
                }
            }) && restored;
            restored = attempt(() -> {
                if (cullEnabled) {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                } else {
                    GL11.glDisable(GL11.GL_CULL_FACE);
                }
                GL11.glCullFace(cullFace);
                GL11.glFrontFace(frontFace);
            }) && restored;

            restored = attempt(() -> {
                if (polygonOffsetEnabled) {
                    RenderSystem.enablePolygonOffset();
                } else {
                    RenderSystem.disablePolygonOffset();
                }
                RenderSystem.polygonOffset(polygonOffsetFactor, polygonOffsetUnits);
            }) && restored;
            restored = attempt(() -> {
                if (polygonOffsetEnabled) {
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                } else {
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                }
                GL11.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
            }) && restored;

            restored = attempt(() -> RenderSystem.colorMask(
                    colorMask[0], colorMask[1], colorMask[2], colorMask[3])) && restored;
            restored = attempt(() -> GL11.glColorMask(
                    colorMask[0], colorMask[1], colorMask[2], colorMask[3])) && restored;
            restored = attempt(() -> {
                if (scissorEnabled) {
                    RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
                } else {
                    RenderSystem.disableScissor();
                }
            }) && restored;
            restored = attempt(() -> {
                if (scissorEnabled) {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                } else {
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                }
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            }) && restored;
            restored = attempt(() -> {
                if (stencilEnabled) {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                } else {
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                }
                GL20.glStencilFuncSeparate(
                        GL11.GL_FRONT, stencilFunctionFront, stencilReferenceFront, stencilValueMaskFront);
                GL20.glStencilMaskSeparate(GL11.GL_FRONT, stencilWriteMaskFront);
                GL20.glStencilOpSeparate(
                        GL11.GL_FRONT, stencilFailFront, stencilDepthFailFront, stencilDepthPassFront);
                GL20.glStencilFuncSeparate(
                        GL11.GL_BACK, stencilFunctionBack, stencilReferenceBack, stencilValueMaskBack);
                GL20.glStencilMaskSeparate(GL11.GL_BACK, stencilWriteMaskBack);
                GL20.glStencilOpSeparate(
                        GL11.GL_BACK, stencilFailBack, stencilDepthFailBack, stencilDepthPassBack);
            }) && restored;
            restored = attempt(() -> {
                if (colorLogicEnabled) {
                    GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
                } else {
                    GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
                }
                GL11.glLogicOp(logicOperation);
                GL11.glLineWidth(lineWidth);
            }) && restored;
            restored = restoreActiveTexture() && restored;
            restored = attempt(() -> GL30.glBindVertexArray(vertexArray)) && restored;
            restored = attempt(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)) && restored;
            return restored;
        }

        private boolean restoreTextureBindings() {
            boolean restored = true;
            for (int unit = 0; unit < SHADER_TEXTURE_COUNT; unit++) {
                final int textureUnit = unit;
                restored = attempt(() -> RenderSystem.setShaderTexture(
                        textureUnit, shaderTextures[textureUnit])) && restored;
                restored = restoreTextureUnit(textureUnit) && restored;
            }
            return restored;
        }

        private boolean restoreTextureUnit(int unit) {
            boolean restored = true;
            restored = attempt(() -> RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit)) && restored;
            restored = attempt(() -> GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D, textureBindings[unit])) && restored;
            return restored;
        }

        private boolean restoreActiveTexture() {
            boolean restored = true;
            restored = attempt(() -> RenderSystem.activeTexture(activeTexture)) && restored;
            restored = attempt(() -> GL13.glActiveTexture(activeTexture)) && restored;
            return restored;
        }

        private static boolean attempt(Runnable operation) {
            try {
                operation.run();
                return true;
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        private static void copy(IntBuffer source, int[] destination) {
            for (int index = 0; index < destination.length; index++) {
                destination[index] = source.get(index);
            }
        }

        private static void copy(FloatBuffer source, float[] destination) {
            for (int index = 0; index < destination.length; index++) {
                destination[index] = source.get(index);
            }
        }
    }
}
