package net.hulan.ksd.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

public class RenderUtilities_1_16_5 extends RenderUtilities {

    public void beginDrawingCircle(BufferBuilder buffer) {
        RenderSystem.disableTexture();
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
    }

    public void beginDrawingTexture(BufferBuilder buffer, ResourceLocation texture) {
        RenderSystem.enableTexture();
        Minecraft.getInstance().getTextureManager().bind(texture);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX);
    }

    public void finishDrawingCircle() {
        RenderSystem.enableTexture();
    }

    public void finishDrawingTexture() {
        RenderSystem.disableTexture();
    }
}
