package net.hulan.ksd.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.InvocationTargetException;

public abstract class RenderUtilities {

    private static RenderUtilities instance;
    private static final float LIGHT_FACTOR = 0.5f;
    private static final Style FONT = Style.EMPTY.withFont(new ResourceLocation("ivr", "mtr"));
    // 「中文在上、英文在下」两行文字之间的行间距（像素）
    private static final float CJK_LINE_GAP = 2.0F;
    // 一行文字的高度（自定义字体字号约 10 像素）
    private static final float TEXT_LINE_HEIGHT = 10.0F;

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
        r = (int) (r + (255 - r) * LIGHT_FACTOR);
        g = (int) (g + (255 - g) * LIGHT_FACTOR);
        b = (int) (b + (255 - b) * LIGHT_FACTOR);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void drawFilledRectangle(PoseStack matrices, float x1, float y1, float x2, float y2, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        beginDrawingCircle(buffer);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        buffer.vertex(matrices.last().pose(), x1, y1, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x1, y2, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x2, y1, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x2, y2, 0)
                .color(r, g, b, a)
                .endVertex();
        tesselator.end();
        finishDrawingCircle();
        RenderSystem.disableBlend();
    }

    public void drawThickLine(PoseStack matrices, float x1, float y1, float x2, float y2, float thickness, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001f) {
            return;
        }
        float nx = -dy / length * thickness / 2;
        float ny = dx / length * thickness / 2;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        beginDrawingCircle(buffer);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        buffer.vertex(matrices.last().pose(), x1 - nx, y1 - ny, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x1 + nx, y1 + ny, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x2 - nx, y2 - ny, 0)
                .color(r, g, b, a)
                .endVertex();
        buffer.vertex(matrices.last().pose(), x2 + nx, y2 + ny, 0)
                .color(r, g, b, a)
                .endVertex();
        tesselator.end();
        finishDrawingCircle();
        RenderSystem.disableBlend();
    }

    // 绘制材质贴图：以 (x, y) 为左上角绘制宽 width 高 height 的四边形（贴图坐标 0~1）
    public void drawTexture(PoseStack matrices, ResourceLocation resourceLocation, float x, float y, float width, float height) {
        // 尺寸非法时直接放弃绘制
        if (width <= 0 || height <= 0) {
            return;
        }
        // 开启混合，让文字透明通道生效
        RenderSystem.enableBlend();
        // 使用默认混合函数
        RenderSystem.defaultBlendFunc();
        // 绑定贴图纹理
        RenderSystem.setShaderTexture(0, resourceLocation);
        // 使用带位置+贴图坐标的着色器
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        // 获取顶点缓冲构造器
        Tesselator tesselator = Tesselator.getInstance();
        // 取得当前 BufferBuilder
        BufferBuilder buffer = tesselator.getBuilder();
        // 开始收集四边形顶点（带贴图坐标）
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // 左上角顶点（贴图坐标 0,0）
        buffer.vertex(matrices.last().pose(), x, y, 0).uv(0, 0).endVertex();
        // 左下角顶点（贴图坐标 0,1）
        buffer.vertex(matrices.last().pose(), x, y + height, 0).uv(0, 1).endVertex();
        // 右下角顶点（贴图坐标 1,1）
        buffer.vertex(matrices.last().pose(), x + width, y + height, 0).uv(1, 1).endVertex();
        // 右上角顶点（贴图坐标 1,0）
        buffer.vertex(matrices.last().pose(), x + width, y, 0).uv(1, 0).endVertex();
        // 提交顶点数据绘制
        tesselator.end();
        // 关闭混合
        RenderSystem.disableBlend();
    }

    public void drawText(PoseStack matrices, String text, float x, float y, float maxWidth, float maxHeight, int color) {
        if (text == null || text.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
            return;
        }
        float textWidth = getTextWidth(text, 1.0F);
        float textHeight = Minecraft.getInstance().font.lineHeight;
        if (textWidth <= 0) {
            return;
        }
        float scale = Math.min(maxWidth / textWidth, maxHeight / textHeight);
        float offsetY = (maxHeight - textHeight * scale) / 2;
        drawText(matrices, text, x, y + offsetY, scale, color);
    }

    // 用自定义字体直接在屏幕上绘制文字（不生成任何贴图）：以 (x, y) 为左上角、按 scale 缩放绘制，
    // 逐字符自动选择字体（拉丁用 Noto Sans、中文用 Noto Serif CJK），color 为 ARGB 颜色
    public void drawText(PoseStack matrices, String text, float x, float y, float scale, int color) {
        // 文本为空时直接跳过
        if (text == null || text.isEmpty()) {
            return;
        }
        // 压入矩阵，以 (x, y) 为原点缩放
        matrices.pushPose();
        // 平移到文字左上角
        matrices.translate(x, y, 0);
        // 按给定倍数缩放
        matrices.scale(scale, scale, 1.0F);
        // 用带自定义字体样式的文本直接绘制（MTR 字体管线，逐字符解析样式字体）
        Minecraft.getInstance().font.draw(matrices, Text.literal(text).withStyle(FONT), 0, 0, color);
        // 弹出矩阵恢复原状
        matrices.popPose();
    }

    public float getTextWidth(String text, float scale) {
        return Minecraft.getInstance().font.width(Text.literal(text).withStyle(FONT)) * scale;
    }

    // 把「中文|English||注释」形式的名称拆成中文与英文两部分：
    // 单个 | 分隔中文与英文，|| 之后为注释部分（直接丢弃），无分隔符时整段视为中文行
    public static String[] splitCjk(String text) {
        if (text == null) {
            return new String[]{""};
        }
        // 先去掉 || 之后的注释部分
        String main = text.split("\\|\\|")[0];
        // 再按单个 | 拆分中文与英文
        return main.split("\\|", -1);
    }

    // 测量「中文在上、英文在下」两行文字的整体尺寸，返回 {宽, 高}（缺少某行时按单行计算）
    public float[] getTextSizeCjk(String text, float cnScale, float enScale) {
        // 拆分中文与英文行（去除首尾空格）
        String[] parts = splitCjk(text);
        String cn = parts.length > 0 ? parts[0].trim() : "";
        String en = parts.length > 1 ? parts[1].trim() : "";
        // 整体宽度累加
        float width = 0;
        // 整体高度累加
        float height = 0;
        // 有中文行时累加宽度与高度
        if (!cn.isEmpty()) {
            width = Math.max(width, getTextWidth(cn, cnScale));
            height += TEXT_LINE_HEIGHT * cnScale;
        }
        // 有英文行时累加宽度与高度，并与中文行之间留行间距
        if (!en.isEmpty()) {
            width = Math.max(width, getTextWidth(en, enScale));
            height += TEXT_LINE_HEIGHT * enScale;
            if (!cn.isEmpty()) {
                height += CJK_LINE_GAP;
            }
        }
        // 返回整体宽高
        return new float[]{width, height};
    }

    // 把「中文||English」形式的名称分成两行绘制：中文（大号）在上、英文（小号）在下，
    // (x, y) 为两行文字整体的左边界与垂直中心，color 为 ARGB 颜色
    public void drawTextCjk(PoseStack matrices, String text, float x, float y, float cnScale, float enScale, int color) {
        // 文本为空时直接跳过
        if (text == null || text.isEmpty()) {
            return;
        }
        // 拆分中文与英文行（去除首尾空格）
        String[] parts = splitCjk(text);
        String cn = parts.length > 0 ? parts[0].trim() : "";
        String en = parts.length > 1 ? parts[1].trim() : "";
        // 两行文字整体为空时直接跳过
        if (cn.isEmpty() && en.isEmpty()) {
            return;
        }
        // 整体高度换算成顶边
        float topY = y - getTextSizeCjk(text, cnScale, enScale)[1] / 2;
        // 当前行顶边游标
        float yCursor = topY;
        // 有中文行时绘制并下移一行
        if (!cn.isEmpty()) {
            drawText(matrices, cn, x, yCursor, cnScale, color);
            yCursor += TEXT_LINE_HEIGHT * cnScale + CJK_LINE_GAP;
        }
        // 有英文行时绘制在中文行下方
        if (!en.isEmpty()) {
            drawText(matrices, en, x, yCursor, enScale, color);
        }
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
