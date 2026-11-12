package net.hulan.ksd.utils;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import net.minecraft.resources.ResourceLocation;

public final class RenderUtilitiesImpl {

    private RenderUtilitiesImpl() {
    }

    public static void beginDrawingCircle(BufferBuilder buffer) {
        RenderSystem.disableTexture();
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
    }

    public static void beginDrawingTexture(BufferBuilder buffer, ResourceLocation texture) {
        RenderSystem.enableTexture();
        Minecraft.getInstance().getTextureManager().bind(texture);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX);
    }

    public static void finishDrawingCircle() {
        RenderSystem.enableTexture();
    }

    public static void finishDrawingTexture() {
        RenderSystem.disableTexture();
    }
}
