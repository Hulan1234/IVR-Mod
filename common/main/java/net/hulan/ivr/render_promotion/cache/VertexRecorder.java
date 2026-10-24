package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Render-thread recording adapter. Call models with an identity PoseStack, never on workers. */
public final class VertexRecorder implements VertexConsumer {

    private final List<VertexMesh.Vertex> vertices = new ArrayList<>();
    private float x, y, z, u, v, nx, ny, nz;
    private int color = -1;
    private int light;
    private int overlay;
    private boolean sawLight;
    private boolean sawOverlay;
    private boolean defaultColor;

    public @NotNull VertexConsumer vertex(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
        return this;
    }
    public @NotNull VertexConsumer color(int r, int g, int b, int a) {
        if (!defaultColor) color = a << 24 | r << 16 | g << 8 | b;
        return this;
    }
    public @NotNull VertexConsumer uv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }
    public @NotNull VertexConsumer overlayCoords(int u, int v) {
        overlay = u | v << 16;
        sawOverlay = true;
        return this;
    }
    public @NotNull VertexConsumer uv2(int u, int v) {
        light = u | v << 16;
        sawLight = true;
        return this;
    }
    public @NotNull VertexConsumer normal(float x, float y, float z) {
        nx = x;
        ny = y;
        nz = z;
        return this;
    }

    public void endVertex() {
        if (vertices.size() >= 65536) throw new IllegalStateException("Mesh too large");
        vertices.add(new VertexMesh.Vertex(x, y, z, u, v, nx, ny, nz, color));
    }

    public void defaultColor(int r, int g, int b, int a) {
        color = a << 24 | r << 16 | g << 8 | b; defaultColor = true;
    }

    public void unsetDefaultColor() {
        defaultColor = false;
    }
    public List<VertexMesh.Vertex> snapshot() {
        return List.copyOf(vertices);
    }

    /** Packed values observed while recording a callback. */
    public int light() {
        return light;
    }
    public int overlay() {
        return overlay;
    }
    public boolean hasLight() {
        return sawLight;
    }
    public boolean hasOverlay() {
        return sawOverlay;
    }

    /**
     * Replays the render-thread snapshot without constructing a VertexMesh.
     * The immutable snapshot is handed to MeshBuildScheduler separately, so a
     * cache miss never performs a second callback invocation or synchronous
     * mesh allocation on the render thread.
     */
    public void replay(PoseStack matrices, VertexConsumer consumer, int replayLight, int replayOverlay) {
        PoseStack.Pose pose = matrices.last();
        for (VertexMesh.Vertex vertex : vertices) {
            int packedColor = vertex.color();
            consumer.vertex(pose.pose(), vertex.x(), vertex.y(), vertex.z())
                    .color(packedColor >>> 16 & 255, packedColor >>> 8 & 255,
                            packedColor & 255, packedColor >>> 24 & 255)
                    .uv(vertex.u(), vertex.v()).overlayCoords(replayOverlay).uv2(replayLight)
                    .normal(pose.normal(), vertex.nx(), vertex.ny(), vertex.nz()).endVertex();
        }
    }
}
