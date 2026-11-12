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

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 350;
    private final Runnable onCloseCallback;
    private final WidgetShorterSlider[] values = new WidgetShorterSlider[6];
    private final Button buttonSave;
    private final Button buttonCancel;
    private final Button buttonReset;
    private final Button buttonCulling;
    private final Button buttonCpuOptimization;
    private final Button buttonGpuOptimization;
    private boolean cullingEnabled;
    private boolean cpuOptimization;
    private boolean gpuOptimization;
    private boolean saveFailed;

    public IVRConfigScreen(Runnable onCloseCallback) {
        super(Text.translatable("gui.ivr.render_config.title"));
        this.onCloseCallback = onCloseCallback;
        for (int index = 0; index < 4; index++) {
            values[index] = new WidgetShorterSlider(0, 0,
                    IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1,
                    value -> Integer.toString((value + 1) * IVRConfig.DISTANCE_STEP), null);
        }
        values[4] = new WidgetShorterSlider(0, 0,
                IVRConfig.MAX_MEMORY_BUDGET_MIB / IVRConfig.MEMORY_BUDGET_STEP_MIB,
                this::memoryBudgetLabel, null);
        values[5] = new WidgetShorterSlider(0, 0,
                (IVRConfig.MAX_MAX_OCCLUSION_RAYS - IVRConfig.MIN_MAX_OCCLUSION_RAYS)
                        / IVRConfig.OCCLUSION_RAY_STEP,
                value -> Integer.toString(IVRConfig.MIN_MAX_OCCLUSION_RAYS
                        + value * IVRConfig.OCCLUSION_RAY_STEP), null);
        buttonSave = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.save"), button -> save());
        buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.cancel"), button -> onClose());
        cullingEnabled = IVRConfig.cullingEnabled;
        cpuOptimization = IVRConfig.cpuOptimization;
        gpuOptimization = IVRConfig.gpuOptimization;
        buttonCulling = UtilitiesClient.newButton(cullingLabel(), button -> toggleCulling());
        buttonCpuOptimization = UtilitiesClient.newButton(cpuOptimizationLabel(), button -> toggleCpuOptimization());
        buttonGpuOptimization = UtilitiesClient.newButton(gpuOptimizationLabel(), button -> toggleGpuOptimization());
        buttonReset = UtilitiesClient.newButton(Text.translatable("gui.ivr.render_config.defaults"), button -> {
            values[0].setValue(IVRConfig.DEFAULT_BE_RD / IVRConfig.DISTANCE_STEP - 1);
            values[1].setValue(IVRConfig.DEFAULT_TRAIN_RD / IVRConfig.DISTANCE_STEP - 1);
            values[2].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
            values[3].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
            values[4].setValue(IVRConfig.DEFAULT_MEMORY_BUDGET_MIB / IVRConfig.MEMORY_BUDGET_STEP_MIB);
            values[5].setValue((IVRConfig.DEFAULT_MAX_OCCLUSION_RAYS
                    - IVRConfig.MIN_MAX_OCCLUSION_RAYS) / IVRConfig.OCCLUSION_RAY_STEP);
            cullingEnabled = IVRConfig.DEFAULT_CULLING_ENABLED;
            cpuOptimization = IVRConfig.DEFAULT_CPU_OPTIMIZATION;
            gpuOptimization = IVRConfig.DEFAULT_GPU_OPTIMIZATION;
            updateToggleLabels();
            saveFailed = false;
        });
    }

    @Override
    protected void init() {
        super.init();
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - PANEL_HEIGHT / 2;
        int[] sliderOffsets = {35, 60, 85, 110, 140, 165};
        for (int index = 0; index < values.length; index++) {
            int y = top + sliderOffsets[index];
            IDrawing.setPositionAndWidth(values[index], left + 125, y, 130);
            values[index].setHeight(20);
            addDrawableChild(values[index]);
        }
        int toggleWidth = (PANEL_WIDTH - 36) / 3;
        IDrawing.setPositionAndWidth(buttonCulling, left + 12, top + 195, toggleWidth);
        IDrawing.setPositionAndWidth(buttonCpuOptimization, left + 18 + toggleWidth, top + 195, toggleWidth);
        IDrawing.setPositionAndWidth(buttonGpuOptimization, left + 24 + toggleWidth * 2, top + 195, toggleWidth);
        IDrawing.setPositionAndWidth(buttonReset, left + 12, top + 315, 104);
        IDrawing.setPositionAndWidth(buttonSave, left + 128, top + 315, 104);
        IDrawing.setPositionAndWidth(buttonCancel, left + 244, top + 315, 104);
        addDrawableChild(buttonCulling);
        addDrawableChild(buttonCpuOptimization);
        addDrawableChild(buttonGpuOptimization);
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
        String[] keys = {"block_entity", "train", "catenary", "nte_rail_box",
                "memory_budget", "occlusion_rays"};
        int[] labelOffsets = {41, 66, 91, 116, 146, 171};
        for (int index = 0; index < keys.length; index++) {
            drawString(matrices, Minecraft.getInstance().font,
                    Text.translatable("gui.ivr.render_config." + keys[index]),
                    left + 12, top + labelOffsets[index], ARGB_WHITE);
        }
        if (saveFailed) {
            drawCenteredString(matrices, Minecraft.getInstance().font,
                    Text.translatable("gui.ivr.render_config.save_failed"), width / 2, top + 285,
                    RGB_RED | ARGB_BLACK);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    private void setValues() {
        values[0].setValue(IVRConfig.blockEntityRD / IVRConfig.DISTANCE_STEP - 1);
        values[1].setValue(IVRConfig.trainRD / IVRConfig.DISTANCE_STEP - 1);
        values[2].setValue(IVRConfig.msdCatenaryRD / IVRConfig.DISTANCE_STEP - 1);
        values[3].setValue(IVRConfig.nteRailBoxRD / IVRConfig.DISTANCE_STEP - 1);
        values[4].setValue(IVRConfig.memoryBudgetMiB / IVRConfig.MEMORY_BUDGET_STEP_MIB);
        values[5].setValue((IVRConfig.maxOcclusionRays - IVRConfig.MIN_MAX_OCCLUSION_RAYS)
                / IVRConfig.OCCLUSION_RAY_STEP);
        cullingEnabled = IVRConfig.cullingEnabled;
        cpuOptimization = IVRConfig.cpuOptimization;
        gpuOptimization = IVRConfig.gpuOptimization;
        updateToggleLabels();
        saveFailed = false;
    }

    private void save() {
        try {
            IVRConfig.save(
                    (values[0].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[1].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[2].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (values[3].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    gpuOptimization,
                    cullingEnabled,
                    cpuOptimization,
                    values[4].getIntValue() * IVRConfig.MEMORY_BUDGET_STEP_MIB,
                    IVRConfig.MIN_MAX_OCCLUSION_RAYS
                            + values[5].getIntValue() * IVRConfig.OCCLUSION_RAY_STEP);
            onClose();
        } catch (IOException exception) {
            IVR.LOGGER.warn("Could not save IVR render config", exception);
            saveFailed = true;
        }
    }

    private net.minecraft.network.chat.Component cullingLabel() {
        return Text.translatable(cullingEnabled
                ? "gui.ivr.render_config.culling.enabled"
                : "gui.ivr.render_config.culling.disabled");
    }

    private net.minecraft.network.chat.Component cpuOptimizationLabel() {
        return Text.translatable(cpuOptimization
                ? "gui.ivr.render_config.cpu_optimization.enabled"
                : "gui.ivr.render_config.cpu_optimization.disabled");
    }

    private net.minecraft.network.chat.Component gpuOptimizationLabel() {
        return Text.translatable(gpuOptimization
                ? "gui.ivr.render_config.gpu_optimization.enabled"
                : "gui.ivr.render_config.gpu_optimization.disabled");
    }

    private String memoryBudgetLabel(int value) {
        return value == 0 ? "auto" : Integer.toString(value * IVRConfig.MEMORY_BUDGET_STEP_MIB);
    }

    private void updateToggleLabels() {
        buttonCulling.setMessage(cullingLabel());
        buttonCpuOptimization.setMessage(cpuOptimizationLabel());
        buttonGpuOptimization.setMessage(gpuOptimizationLabel());
    }

    private void toggleCulling() {
        cullingEnabled = !cullingEnabled;
        buttonCulling.setMessage(cullingLabel());
    }

    private void toggleCpuOptimization() {
        cpuOptimization = !cpuOptimization;
        buttonCpuOptimization.setMessage(cpuOptimizationLabel());
    }

    private void toggleGpuOptimization() {
        gpuOptimization = !gpuOptimization;
        buttonGpuOptimization.setMessage(gpuOptimizationLabel());
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
