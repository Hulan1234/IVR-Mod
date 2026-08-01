package net.hulan.ksd.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.renderer.GameRenderer;

public class RenderUtilities_1_19_4 extends RenderUtilities {

    public void beginDrawingCircle(BufferBuilder buffer) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
    }

    public void finishDrawingCircle() {
    }
}
