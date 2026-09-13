package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.ButtonMapper;
import mtr.mappings.UtilitiesClient;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** A button that can display a full-size texture while retaining its normal click behavior. */
public class TextureButton extends ButtonMapper {

    private static final int MISSING_TEXTURE_BACKGROUND = 0xFF4A4A4A;
    private static final int HOVER_BORDER_COLOR = 0xFFFFFFFF;
    private static final int HOVER_BORDER_WIDTH = 2;
    private ResourceLocation texture;

    public TextureButton(Component message, Button.OnPress onPress) {
        this(null, message, onPress);
    }

    public TextureButton(ResourceLocation texture, Component message, Button.OnPress onPress) {
        super(0, 0, 0, 0, message, onPress);
        this.texture = texture;
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
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int widgetWidth = getWidth();
        final int widgetHeight = getHeight();
        if (hasTexture()) {
            RenderUtilities.getInstance().drawTexture(matrices, texture, widgetX, widgetY, widgetWidth, widgetHeight);
        } else {
            drawMissingTexture(matrices);
        }
        drawMessage(matrices);
        if (isMouseOver(mouseX, mouseY)) {
            drawHoverBorder(matrices);
        }
    }

    private boolean hasTexture() {
        try {
            return UtilitiesClient.hasResource(texture);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void drawMissingTexture(PoseStack matrices) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int widgetWidth = getWidth();
        final int widgetHeight = getHeight();
        Gui.fill(matrices, widgetX, widgetY, widgetX + widgetWidth, widgetY + widgetHeight, MISSING_TEXTURE_BACKGROUND);
    }

    private void drawMessage(PoseStack matrices) {
        if (getMessage().getString().isEmpty()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final int messageWidth = minecraft.font.width(getMessage());
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int widgetWidth = getWidth();
        final int widgetHeight = getHeight();
        minecraft.font.draw(
                matrices,
                getMessage(),
                widgetX + (widgetWidth - messageWidth) / 2.0F,
                widgetY + (widgetHeight - 8) / 2.0F,
                0xFFFFFFFF);
    }

    private void drawHoverBorder(PoseStack matrices) {
        final int widgetX = UtilitiesClient.getWidgetX(this);
        final int widgetY = UtilitiesClient.getWidgetY(this);
        final int right = widgetX + getWidth();
        final int bottom = widgetY + getHeight();
        Gui.fill(matrices, widgetX, widgetY, right, widgetY + HOVER_BORDER_WIDTH, HOVER_BORDER_COLOR);
        Gui.fill(matrices, widgetX, bottom - HOVER_BORDER_WIDTH, right, bottom, HOVER_BORDER_COLOR);
        Gui.fill(matrices, widgetX, widgetY + HOVER_BORDER_WIDTH, widgetX + HOVER_BORDER_WIDTH, bottom - HOVER_BORDER_WIDTH, HOVER_BORDER_COLOR);
        Gui.fill(matrices, right - HOVER_BORDER_WIDTH, widgetY + HOVER_BORDER_WIDTH, right, bottom - HOVER_BORDER_WIDTH, HOVER_BORDER_COLOR);
    }
}
