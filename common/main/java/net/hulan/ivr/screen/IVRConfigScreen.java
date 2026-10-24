package net.hulan.ivr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.screen.WidgetShorterSlider;
import net.hulan.ivr.IVR;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ksd.sreen.KSDGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class IVRConfigScreen extends ScreenMapper implements KSDGui {

    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 210;
    private final Runnable onCloseCallback;
    private final WidgetShorterSlider[] values = new WidgetShorterSlider[4];
    private final Button buttonSave;
    private final Button buttonCancel;
    private final Button buttonReset;
    private boolean saveFailed;

    public IVRConfigScreen(Runnable onCloseCallback) {
        super(Text.translatable("gui.ivr.render_config.title"));
        this.onCloseCallback = onCloseCallback;
        for (int index = 0; index < values.length; index++) {
            values[index] = new WidgetShorterSlider(0, 0,
                    IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1,
                    value -> Integer.toString((value + 1) * IVRConfig.DISTANCE_STEP), null);
        }
        buttonSave = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.save"), button -> save());
        buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.cancel"), button -> onClose());
        buttonReset = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.defaults"), button -> {
            values[0].setValue(IVRConfig.DEFAULT_BE_RD / IVRConfig.DISTANCE_STEP - 1);
            values[1].setValue(IVRConfig.DEFAULT_TRAIN_RD / IVRConfig.DISTANCE_STEP - 1);
            values[2].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
            values[3].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
            saveFailed = false;
        });
    }

    @Override
    protected void init() {
        super.init();
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - PANEL_HEIGHT / 2;
        for (int index = 0; index < values.length; index++) {
            int y = top + 42 + index * 30;
            IDrawing.setPositionAndWidth(values[index], left + 125, y, 130);
            values[index].setHeight(20);
            addDrawableChild(values[index]);
        }
        IDrawing.setPositionAndWidth(buttonReset, left + 12, top + 175, 84);
        IDrawing.setPositionAndWidth(buttonSave, left + 108, top + 175, 84);
        IDrawing.setPositionAndWidth(buttonCancel, left + 204, top + 175, 84);
        addDrawableChild(buttonReset);
        addDrawableChild(buttonSave);
        addDrawableChild(buttonCancel);
        IVRConfig.load();
        setValues();
    }

    @Override
    public void render(@NotNull PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        KSDGui.renderBg(matrices, width, height, PANEL_WIDTH, PANEL_HEIGHT);
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - PANEL_HEIGHT / 2;
        drawCenteredString(matrices, Minecraft.getInstance().font, title, width / 2, top + 13, ARGB_WHITE);
        String[] keys = {"block_entity", "train", "catenary", "nte_rail_box"};
        for (int index = 0; index < keys.length; index++) {
            drawString(matrices, Minecraft.getInstance().font,
                    Text.translatable("gui.ivr.render_config." + keys[index]),
                    left + 12, top + 48 + index * 30, ARGB_WHITE);
        }
        if (saveFailed) {
            drawCenteredString(matrices, Minecraft.getInstance().font,
                    Text.translatable("gui.ivr.render_config.save_failed"), width / 2, top + 160,
                    RGB_RED | ARGB_BLACK);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    private void setValues() {
        values[0].setValue(IVRConfig.blockEntityRD / IVRConfig.DISTANCE_STEP - 1);
        values[1].setValue(IVRConfig.trainRD / IVRConfig.DISTANCE_STEP - 1);
        values[2].setValue(IVRConfig.msdCatenaryRD / IVRConfig.DISTANCE_STEP - 1);
        values[3].setValue(IVRConfig.nteRailBoxRD / IVRConfig.DISTANCE_STEP - 1);
        saveFailed = false;
    }

    private void save() {
        try {
            IVRConfig.save(
                    (values[0].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[1].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[2].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[3].getIntValue() + 1) * IVRConfig.DISTANCE_STEP);
            onClose();
        } catch (IOException exception) {
            IVR.LOGGER.warn("Could not save IVR render config", exception);
            saveFailed = true;
        }
    }

    @Override
    public void onClose() {
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
