package net.hulan.ksd.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.ButtonMapper;
import mtr.mappings.UtilitiesClient;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选的贴图/样式按钮：
 * <ul>
 *     <li>{@link Style#RECTANGLE}：矩形</li>
 *     <li>{@link Style#CIRCLE}：内切圆形</li>
 *     <li>{@link Style#ROUNDED}：四角为半圆的圆角矩形</li>
 * </ul>
 * 鼠标停留时在边框内侧 1 像素处绘制更小的 1 像素白色描边。
 */
public class BetterButton extends ButtonMapper {

    public enum Style {
        RECTANGLE,
        CIRCLE,
        ROUNDED
    }

    private static final int BACKGROUND_COLOR = 0xFF4A4A4A;
    // 浅青蓝半透明背景（RGB 60E9FF，A 113）
    public static final int BACKGROUND_CYAN = 0x7160E9FF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR_INACTIVE = 0xFF9A9A9A;
    private static final int BORDER_COLOR = 0x80FFFFFF;
    private static final int BORDER_COLOR_HOVERED = 0xFFFFFFFF;
    private static final int TEXT_PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_HEIGHT = 8;
    // 与原版按钮一致的默认高度（IDrawing.setPositionAndWidth 不会设置高度）
    private static final int DEFAULT_HEIGHT = 20;

    private final Style style;
    private ResourceLocation texture;
    private int backgroundColor;

    public BetterButton(Component message, Button.OnPress onPress) {
        this(Style.RECTANGLE, null, BACKGROUND_COLOR, message, onPress);
    }

    public BetterButton(ResourceLocation texture, Component message, Button.OnPress onPress) {
        this(Style.RECTANGLE, texture, BACKGROUND_COLOR, message, onPress);
    }

    public BetterButton(Style style, Component message, Button.OnPress onPress) {
        this(style, null, BACKGROUND_COLOR, message, onPress);
    }

    public BetterButton(Style style, int backgroundColor, Component message, Button.OnPress onPress) {
        this(style, null, backgroundColor, message, onPress);
    }

    public BetterButton(Style style, ResourceLocation texture, Component message, Button.OnPress onPress) {
        this(style, texture, BACKGROUND_COLOR, message, onPress);
    }

    public BetterButton(Style style, ResourceLocation texture, int backgroundColor, Component message, Button.OnPress onPress) {
        super(0, 0, 0, DEFAULT_HEIGHT, message, onPress);
        this.style = style;
        this.texture = texture;
        this.backgroundColor = backgroundColor;
    }

    public Style getStyle() {
        return style;
    }

    public void setBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public ResourceLocation getTexture() {
        return texture;
    }

    public void setTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    public void setLayoutHeight(int height) {
        this.height = height;
    }

    @Override
    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }
        drawBackground(matrices);
        drawMessage(matrices);
        drawBorder(matrices, mouseX, mouseY);
    }

    private void drawBackground(PoseStack matrices) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final float[] shape = getShapeBounds(widgetX, widgetY);
        if (hasTexture()) {
            if (style == Style.RECTANGLE) {
                RenderUtilities.drawTexture(matrices, texture, widgetX, widgetY, getWidth(), getHeight());
            } else {
                RenderUtilities.drawTextureRounded(matrices, texture, shape[0], shape[1], shape[2], shape[3], shape[4]);
            }
        } else {
            // 背景跟随按钮形状绘制，圆角/圆形样式不会在四角露出直角边框
            final int color = this.active ? this.backgroundColor : dimColor(this.backgroundColor);
            RenderUtilities.drawRoundedRectangle(matrices, shape[0], shape[1], shape[2], shape[3], shape[4], color);
        }
    }

    // 不可用状态：保持透明度、把颜色压暗
    private static int dimColor(int color) {
        final int alpha = (color >>> 24) & 0xFF;
        final int red = (int)(((color >> 16) & 0xFF) * 0.75);
        final int green = (int)(((color >> 8) & 0xFF) * 0.75);
        final int blue = (int)((color & 0xFF) * 0.75);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private void drawBorder(PoseStack matrices, int mouseX, int mouseY) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final float[] shape = getShapeBounds(widgetX, widgetY);
        if (isMouseOver(mouseX, mouseY)) {
            // 悬停：向内缩 1 像素的 1 像素白色描边
            RenderUtilities.drawRoundedRectangleOutline(matrices, shape[0] + 1.0F, shape[1] + 1.0F,
                    Math.max(0.0F, shape[2] - 2.0F), Math.max(0.0F, shape[3] - 2.0F),
                    Math.max(0.0F, shape[4] - 1.0F), BORDER_COLOR_HOVERED);
        } else {
            // 未悬停：贴着按钮宽高的描边
            RenderUtilities.drawRoundedRectangleOutline(matrices, shape[0], shape[1], shape[2], shape[3], shape[4], BORDER_COLOR);
        }
    }

    // 返回 {left, top, width, height, radius}，圆形按内切圆计算
    private float[] getShapeBounds(int widgetX, int widgetY) {
        final int widgetWidth = getWidth();
        final int widgetHeight = getHeight();
        switch (style) {
            case CIRCLE -> {
                final float diameter = Math.min(widgetWidth, widgetHeight);
                return new float[]{
                        widgetX + (widgetWidth - diameter) / 2.0F,
                        widgetY + (widgetHeight - diameter) / 2.0F,
                        diameter, diameter, diameter / 2.0F};
            }
            case ROUNDED -> {
                return new float[]{widgetX, widgetY, widgetWidth, widgetHeight, widgetHeight / 2.0F};
            }
            default -> {
                return new float[]{widgetX, widgetY, widgetWidth, widgetHeight, 0.0F};
            }
        }
    }

    private boolean hasTexture() {
        try {
            return UtilitiesClient.hasResource(texture);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void drawMessage(PoseStack matrices) {
        final String message = getMessage().getString();
        if (message.isEmpty() || getWidth() <= TEXT_PADDING * 2 || getHeight() <= TEXT_PADDING * 2) {
            return;
        }
        final int availableWidth = getWidth() - TEXT_PADDING * 2;
        final List<String> lines = wrapMessage(message, availableWidth);
        if (lines.isEmpty()) {
            return;
        }
        final int textHeight = TEXT_HEIGHT + (lines.size() - 1) * LINE_HEIGHT;
        int widestLine = 0;
        for (String line : lines) {
            widestLine = Math.max(widestLine, (int)Math.ceil(RenderUtilities.getTextWidth(line, 1.0F)));
        }
        final float scale = Math.min(1.0F, Math.min(
                widestLine == 0 ? 1.0F : (float) availableWidth / widestLine,
                (float) (getHeight() - TEXT_PADDING * 2) / textHeight));
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int textColor = this.active ? TEXT_COLOR : TEXT_COLOR_INACTIVE;
        matrices.pushPose();
        matrices.translate(widgetX + getWidth() / 2.0F, widgetY + getHeight() / 2.0F, 0);
        matrices.scale(scale, scale, 1.0F);
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final float lineWidth = RenderUtilities.getTextWidth(line, 1.0F);
            final float lineY = -textHeight / 2.0F + i * LINE_HEIGHT;
            // 使用 RenderUtilities 的 ivr:mtr 字体绘制，CJK/英文走各自的方法
            if (RenderUtilities.containsCjk(line)) {
                RenderUtilities.drawCjkText(matrices, line, -lineWidth / 2.0F, lineY, 1.0F, textColor);
            } else {
                RenderUtilities.drawEnglishText(matrices, line, -lineWidth / 2.0F, lineY, 1.0F, textColor);
            }
        }
        matrices.popPose();
    }

    private static List<String> wrapMessage(String message, int maxWidth) {
        final List<String> lines = new ArrayList<>();
        final StringBuilder line = new StringBuilder();
        final StringBuilder word = new StringBuilder();
        boolean pendingSpace = false;
        for (int offset = 0; offset < message.length();) {
            final int codePoint = message.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                appendWord(lines, line, word, pendingSpace, maxWidth);
                lines.add(line.toString());
                line.setLength(0);
                pendingSpace = false;
            } else if (Character.isWhitespace(codePoint)) {
                appendWord(lines, line, word, pendingSpace, maxWidth);
                pendingSpace = true;
            } else if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                appendWord(lines, line, word, pendingSpace, maxWidth);
                pendingSpace = false;
                appendToken(lines, line, new String(Character.toChars(codePoint)), false, maxWidth);
            } else {
                word.appendCodePoint(codePoint);
            }
        }
        appendWord(lines, line, word, pendingSpace, maxWidth);
        lines.add(line.toString());
        return lines;
    }

    private static void appendWord(List<String> lines, StringBuilder line, StringBuilder word,
                                   boolean pendingSpace, int maxWidth) {
        if (!word.isEmpty()) {
            appendToken(lines, line, word.toString(), pendingSpace, maxWidth);
            word.setLength(0);
        }
    }

    private static void appendToken(List<String> lines, StringBuilder line, String token,
                                    boolean spaceBefore, int maxWidth) {
        final String candidate = line.isEmpty() ? token : line + (spaceBefore ? " " : "") + token;
        if (!line.isEmpty() && RenderUtilities.getTextWidth(candidate, 1.0F) > maxWidth) {
            lines.add(line.toString());
            line.setLength(0);
        }
        if (!line.isEmpty() && spaceBefore) {
            line.append(' ');
        }
        line.append(token);
    }
}
