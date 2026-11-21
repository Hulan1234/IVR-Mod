package net.hulan.ksd.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.IGui;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.util.Mth;

/**
 * 竖直滑动条组件：半透明浅灰圆角轨道 + 横向扁椭圆滑块。
 * 点击或拖动时按 0（底部）~ 1（顶部）回调 {@link KSDScrollBarCallback}。
 */
public class BetterScrollBar implements WidgetMapper, IGui {

    @FunctionalInterface
    public interface KSDScrollBarCallback {

        /**
         * 拖动或点击滑动条时回调。
         *
         * @param value 0（最底部）~ 1（最顶部）之间的位置
         */
        void onScrollBarChanged(double value);
    }

    // 轨道：半透明浅灰，任何状态下都不高亮
    private static final int ARGB_TRACK = 0x80C0C0C0;
    // 滑块：常态白色，悬停或拖动时海蓝色
    private static final int ARGB_THUMB = 0xFFF2F2F2;
    private static final int ARGB_THUMB_ACTIVE = 0xFF1E90FF;
    // 滑块尺寸：宽大于轨道宽度，形成横着的扁椭圆
    private static final int THUMB_WIDTH = 14;
    private static final int THUMB_HEIGHT = 6;
    private static final int HIT_PADDING = 4;

    private final KSDScrollBarCallback callback;
    private int x;
    private int y;
    private int width;
    private int height;
    private double value;
    private boolean dragging;

    public BetterScrollBar(double value, KSDScrollBarCallback callback) {
        this.callback = callback;
        this.value = Mth.clamp(value, 0.0F, 1.0F);
    }

    public void setPositionAndSize(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public double getValue() {
        return this.value;
    }

    /** 只更新显示位置，不触发回调（避免与外部同步时来回触发）。 */
    public void setValue(double value) {
        this.value = Mth.clamp(value, 0.0F, 1.0F);
    }

    public boolean isDragging() {
        return this.dragging;
    }

    public int getThumbHeight() {
        return Math.min(THUMB_HEIGHT, this.height);
    }

    /** 滑块中心的屏幕 Y 坐标，供外部绘制数值提示使用。 */
    public int getThumbCenterY() {
        int thumbHeight = getThumbHeight();
        int centerAtBottom = this.y + this.height - thumbHeight / 2;
        int centerAtTop = this.y + thumbHeight / 2;
        return (int)Math.round(centerAtBottom + (centerAtTop - centerAtBottom) * this.value);
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        int thumbHeight = getThumbHeight();
        int thumbCenterY = getThumbCenterY();
        double thumbLeft = this.x + this.width / 2.0 - THUMB_WIDTH / 2.0;
        RenderUtilities.drawRoundedRectangle(matrices, this.x, this.y, this.width, this.height,
                Math.min(this.width, this.height) / 2.0F, ARGB_TRACK);
        RenderUtilities.drawRoundedRectangle(matrices, (float)thumbLeft, thumbCenterY - thumbHeight / 2.0F,
                THUMB_WIDTH, thumbHeight, Math.min(THUMB_WIDTH, thumbHeight) / 2.0F,
                isThumbActive(mouseX, mouseY) ? ARGB_THUMB_ACTIVE : ARGB_THUMB);
    }

    // 鼠标在滑块上或正在拖动时，滑块显示为海蓝色
    private boolean isThumbActive(int mouseX, int mouseY) {
        if (this.dragging) {
            return true;
        }
        int thumbHeight = getThumbHeight();
        int thumbCenterY = getThumbCenterY();
        double thumbLeft = this.x + this.width / 2.0 - THUMB_WIDTH / 2.0;
        return mouseX >= thumbLeft - HIT_PADDING
                && mouseX <= thumbLeft + THUMB_WIDTH + HIT_PADDING
                && mouseY >= thumbCenterY - thumbHeight / 2.0 - HIT_PADDING
                && mouseY <= thumbCenterY + thumbHeight / 2.0 + HIT_PADDING;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= (double)(this.x - HIT_PADDING)
                && mouseX <= (double)(this.x + this.width + HIT_PADDING)
                && mouseY >= (double)(this.y - HIT_PADDING)
                && mouseY <= (double)(this.y + this.height + HIT_PADDING);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            this.dragging = true;
            setValueFromMouse(mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.dragging) {
            setValueFromMouse(mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragging) {
            this.dragging = false;
            return true;
        }
        return false;
    }

    public void setFocused(boolean focused) {
    }

    public boolean isFocused() {
        return false;
    }

    private void setValueFromMouse(double mouseY) {
        int thumbHeight = getThumbHeight();
        int travel = Math.max(1, this.height - thumbHeight);
        this.value = Mth.clamp((this.y + this.height - thumbHeight / 2.0 - mouseY) / travel, 0.0F, 1.0F);
        this.callback.onScrollBarChanged(this.value);
    }
}
