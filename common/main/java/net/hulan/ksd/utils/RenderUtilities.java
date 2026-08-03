package net.hulan.ksd.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;

import java.lang.reflect.InvocationTargetException;

public abstract class RenderUtilities {

    private static RenderUtilities instance;
    private static final float lightFactor = 0.5f;

    public static RenderUtilities getInstance() {
        if (instance == null || instance instanceof NullRenderUtilities) {
            String version = Utilities.getMinecraftVersion();
            String className = "RenderUtilities_" + version;
            RenderUtilities tempInstance = new NullRenderUtilities();
            try {
                Class<?> clazz = Class.forName("net.hulan.ksd.utils." + className);
                tempInstance = (RenderUtilities) clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException |
                     NoSuchMethodException e) {
                e.printStackTrace();
            }
            instance = tempInstance;
        }
        return instance;
    }

    public void drawStationCircle(PoseStack matrices,
                                  float centerX, float centerY,
                                  float radius, int segments,
                                  float borderThickness, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        beginDrawingCircle(buffer);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float innerRadius = radius - borderThickness;
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float outerX = centerX + cos * radius;
            float outerY = centerY + sin * radius;
            float innerX = centerX + cos * innerRadius;
            float innerY = centerY + sin * innerRadius;
            buffer.vertex(matrices.last().pose(), outerX, outerY, 0)
                    .color(r, g, b, a)
                    .endVertex();
            buffer.vertex(matrices.last().pose(), innerX, innerY, 0)
                    .color(r, g, b, a)
                    .endVertex();
        }
        tesselator.end();
        finishDrawingCircle();
        RenderSystem.disableBlend();
    }

    public int lightenColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * lightFactor);
        g = (int) (g + (255 - g) * lightFactor);
        b = (int) (b + (255 - b) * lightFactor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public abstract void beginDrawingCircle(BufferBuilder buffer);

    public abstract void finishDrawingCircle();

    static class NullRenderUtilities extends RenderUtilities {

        public NullRenderUtilities() {
        }

        public void beginDrawingCircle(BufferBuilder buffer) {
        }

        public void finishDrawingCircle() {
        }
    }
}
