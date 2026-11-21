package net.hulan.ksd.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.UtilitiesClient;
import mtr.screen.WidgetBetterTextField;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * 带样式与顶部提示气泡的输入框：
 * <ul>
 *     <li>{@link Style#RECTANGLE}：矩形</li>
 *     <li>{@link Style#ROUNDED}：四角为半圆的圆角矩形</li>
 * </ul>
 * {@code tipText} 不为空且输入框为空时，在输入框左上方绘制一个白底深色描边的气泡（空间不足时翻到下方）。
 */
public class BetterTextField extends WidgetBetterTextField {

    public enum Style {
        RECTANGLE,
        ROUNDED
    }

    private static final int DEFAULT_MAX_LENGTH = 128;
    private static final int BORDER_COLOR = 0x80FFFFFF;
    private static final int BORDER_COLOR_FOCUSED = 0xFFFFFFFF;
    private static final int TIP_BACKGROUND = 0xFFFFFFFF;
    private static final int TIP_BORDER = 0xFF2B2F36;
    private static final int TIP_TEXT_COLOR = 0xFF2B2F36;
    private static final int TIP_PADDING = 2;
    private static final int TIP_TEXT_HEIGHT = 8;
    private static final int TIP_GAP = 0;
    private static final int TIP_TAIL_WIDTH = 10;
    private static final int TIP_TAIL_HEIGHT = 5;
    private static final int TIP_TAIL_OFFSET = 10;
    private static final float TIP_CORNER_RADIUS = 4.0F;

    private final Style style;
    private final int backgroundColor;
    private String tipText;
    private boolean focused;

    public BetterTextField(String tipText) {
        this(Style.ROUNDED, BetterButton.BACKGROUND_CYAN, tipText);
    }

    public BetterTextField(Style style, int backgroundColor, String tipText) {
        super("", "", DEFAULT_MAX_LENGTH);
        this.style = style;
        this.backgroundColor = backgroundColor;
        this.tipText = tipText;
        // 背景由本类绘制，关闭原版输入框底图
        setBordered(false);
    }

    public BetterTextField(TextFieldFilter filter, String tipText, int maxLength) {
        this(Style.ROUNDED, BetterButton.BACKGROUND_CYAN, filter, tipText, maxLength);
    }

    public BetterTextField(Style style, int backgroundColor, TextFieldFilter filter, String tipText, int maxLength) {
        super(filter, "", maxLength);
        this.style = style;
        this.backgroundColor = backgroundColor;
        this.tipText = tipText;
        setBordered(false);
    }

    public void setTipText(String tipText) {
        this.tipText = tipText;
    }

    public String getTipText() {
        return tipText;
    }

    public Style getStyle() {
        return style;
    }

    public void setFocused(boolean focused) {
        super.setFocused(focused);
        this.focused = focused;
    }

    public boolean isFocused() {
        return this.focused;
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }
        drawShape(matrices, mouseX, mouseY);
        // 文字与光标的起点从 (x + 4, y + (height - 8) / 2) 平移到左半圆圆心 (x + r, y + r)
        matrices.pushPose();
        matrices.translate(getTextOffsetX(), getTextOffsetY(), 0.0);
        enableTextScissor();
        super.render(matrices, mouseX, mouseY, delta);
        disableTextScissor();
        matrices.popPose();
        drawTip(matrices);
    }

    // 文字与光标区域从左侧半径到 width - 半径；1.19+ 的 EditBox 在 bordered=false 时从 x 开始绘制，
    // 因此把绘制原点整体平移到 x + inset（ROUNDED 时为半径，RECTANGLE 时为 4）
    private double getTextInsetX() {
        return style == Style.ROUNDED ? getHeight() / 2.0 : 4.0;
    }

    private double getTextOffsetX() {
        return getTextInsetX();
    }

    private double getTextOffsetY() {
        return style == Style.ROUNDED ? Math.max(0.0, Math.min(4.0, (getHeight() - 8) / 2.0)) : 0.0;
    }

    // 可见文字宽度：从 x + inset 到 x + width - inset，再留 1 像素给光标
    // 原版 EditBox 的滚动（displayPos）和截断都基于 getInnerWidth()，重写后光标会自己停在可见区域内
    @Override
    public int getInnerWidth() {
        return Math.max(0, getWidth() - (int)Math.ceil(getTextInsetX() * 2.0) - 1);
    }

    // 把文字/光标裁剪在 [x + inset, x + width - inset] 内，避免压到半圆描边
    private void enableTextScissor() {
        final Minecraft minecraft = Minecraft.getInstance();
        final double guiScale = Math.max(1.0, minecraft.getWindow().getGuiScale());
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int inset = style == Style.ROUNDED ? getHeight() / 2 : 4;
        final int left = (int)Math.round((widgetX + inset) * guiScale);
        final int right = (int)Math.round((widgetX + getWidth() - inset) * guiScale);
        final int top = (int)Math.round(widgetY * guiScale);
        final int bottom = (int)Math.round((widgetY + getHeight()) * guiScale);
        RenderSystem.enableScissor(left, minecraft.getWindow().getHeight() - bottom,
                Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private void disableTextScissor() {
        RenderSystem.disableScissor();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        // 渲染时整体右移了 getTextOffsetX()，点击坐标要减回去才能对准字符
        return super.mouseClicked(clampToTextAreaX(mouseX - getTextOffsetX()),
                clampToTextAreaY(mouseY - getTextOffsetY()), button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return super.mouseDragged(clampToTextAreaX(mouseX - getTextOffsetX()),
                clampToTextAreaY(mouseY - getTextOffsetY()), button, deltaX, deltaY);
    }

    // 保证补偿后的坐标仍落在控件内，避免点击边缘时光标定位失效
    private double clampToTextAreaX(double mouseX) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        return Math.max(widgetX, Math.min(mouseX, widgetX + getWidth()));
    }

    private double clampToTextAreaY(double mouseY) {
        final int widgetY = UtilitiesClient.getWidgetY(this);
        return Math.max(widgetY + 1.0, Math.min(mouseY, widgetY + getHeight() - 1.0));
    }

    private void drawShape(PoseStack matrices, int mouseX, int mouseY) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final float radius = style == Style.ROUNDED ? getHeight() / 2.0F : 0.0F;
        RenderUtilities.drawRoundedRectangle(matrices, widgetX, widgetY, getWidth(), getHeight(), radius, backgroundColor);
        if (this.focused || isMouseOver(mouseX, mouseY)) {
            RenderUtilities.drawRoundedRectangleOutline(matrices, widgetX + 1.0F, widgetY + 1.0F,
                    Math.max(0.0F, getWidth() - 2.0F), Math.max(0.0F, getHeight() - 2.0F),
                    Math.max(0.0F, radius - 1.0F), BORDER_COLOR_FOCUSED);
        } else {
            RenderUtilities.drawRoundedRectangleOutline(matrices, widgetX, widgetY, getWidth(), getHeight(), radius, BORDER_COLOR);
        }
    }

    private void drawTip(PoseStack matrices) {
        if (tipText == null || tipText.isEmpty() || !getValue().isEmpty()) {
            return;
        }
        final float textWidth = RenderUtilities.getTextWidth(tipText, 1.0F);
        final int bubbleWidth = (int)Math.ceil(textWidth) + TIP_PADDING * 2;
        final int bubbleHeight = TIP_TEXT_HEIGHT + TIP_PADDING * 2;
        final int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int bubbleX = Math.max(1, Math.min(widgetX, screenWidth - bubbleWidth - 1));
        final boolean flipBelow = widgetY - TIP_GAP - TIP_TAIL_HEIGHT - bubbleHeight < 0;
        final int bubbleY = flipBelow
                ? widgetY + getHeight() + TIP_GAP + TIP_TAIL_HEIGHT
                : widgetY - TIP_GAP - TIP_TAIL_HEIGHT - bubbleHeight;
        final int tailCenterX = bubbleX + TIP_TAIL_OFFSET;
        RenderUtilities.drawRoundedRectangle(matrices, bubbleX, bubbleY, bubbleWidth, bubbleHeight, TIP_CORNER_RADIUS, TIP_BACKGROUND);
        RenderUtilities.drawRoundedRectangleOutline(matrices, bubbleX, bubbleY, bubbleWidth, bubbleHeight, TIP_CORNER_RADIUS, TIP_BORDER);
        // 指向输入框的小三角（翻转到下方时朝上）
        drawTail(matrices, tailCenterX, flipBelow ? bubbleY : bubbleY + bubbleHeight, flipBelow);
        RenderUtilities.drawCjkText(matrices, tipText, bubbleX + TIP_PADDING, bubbleY + TIP_PADDING, 1.0F, TIP_TEXT_COLOR);
    }

    // 逐行绘制带描边的小三角；pointingUp 时从气泡上边缘向上生长
    private static void drawTail(PoseStack matrices, int centerX, int baseY, boolean pointingUp) {
        // 先用背景色盖掉气泡与箭头交界处的那一行描边，去掉中间的横线
        final int baseRowWidth = Math.max(1, TIP_TAIL_WIDTH);
        final int baseRowY = pointingUp ? baseY : baseY - 1;
        Gui.fill(matrices, centerX - baseRowWidth / 2, baseRowY,
                centerX - baseRowWidth / 2 + baseRowWidth, baseRowY + 1, TIP_BACKGROUND);
        for (int i = 0; i < TIP_TAIL_HEIGHT; ++i) {
            final int rowWidth = Math.max(1, (int)Math.round(TIP_TAIL_WIDTH * (1.0 - (double)i / TIP_TAIL_HEIGHT)));
            final int left = centerX - rowWidth / 2;
            final int rowY = pointingUp ? baseY - 1 - i : baseY + i;
            if (i == 0) {
                Gui.fill(matrices, left, rowY, left + rowWidth, rowY + 1, TIP_BACKGROUND);
            } else {
                Gui.fill(matrices, left, rowY, left + rowWidth, rowY + 1, TIP_BORDER);
                if (rowWidth > 2) {
                    Gui.fill(matrices, left + 1, rowY, left + rowWidth - 1, rowY + 1, TIP_BACKGROUND);
                }
            }
        }
    }
}
