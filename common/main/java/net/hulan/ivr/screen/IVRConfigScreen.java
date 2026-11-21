package net.hulan.ivr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.screen.WidgetShorterSlider;
import net.hulan.ivr.IVR;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ksd.sreen.KSDGui;
import net.hulan.ksd.widget.BetterButton;
import net.hulan.ksd.widget.BetterScrollBar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 渲染配置界面：五类剔除独立开关 + 通用 BE 边界学习 + 射线/内存预算 + CPU/GPU。
 * 面板内容固定，超出可视区域时由右侧滚动条平移。
 */
public class IVRConfigScreen extends ScreenMapper implements KSDGui {

    private static final int PANEL_WIDTH = 360;
    private static final int MAX_PANEL_HEIGHT = 340;
    private static final int CONTENT_MARGIN = 12;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - CONTENT_MARGIN * 2;
    private static final int FOOTER_HEIGHT = 40;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SECTION_COLOR = 0xFFFFC24B;

    // 内容区内的行位置（不含滚动偏移）
    private static final int SECTION_CULLING = 0;
    private static final int ROW_BE = 18;
    private static final int ROW_TRAIN = 44;
    private static final int ROW_RAIL_BOX = 70;
    private static final int ROW_CATENARY = 96;
    private static final int ROW_EYE_CANDY = 122;
    private static final int SECTION_BOUNDS = 152;
    private static final int ROW_LEARNING = 170;
    private static final int ROW_DESC_1 = 194;
    private static final int ROW_DESC_2 = 206;
    private static final int ROW_RAYS = 224;
    private static final int ROW_MEMORY = 250;
    private static final int SECTION_PERFORMANCE = 280;
    private static final int ROW_OPTIMIZATIONS = 296;
    private static final int CONTENT_HEIGHT = 322;

    private static final int SLIDER_BE = 0;
    private static final int SLIDER_TRAIN = 1;
    private static final int SLIDER_RAIL_BOX = 2;
    private static final int SLIDER_CATENARY = 3;
    private static final int SLIDER_EYE_CANDY = 4;
    private static final int SLIDER_RAYS = 5;
    private static final int SLIDER_MEMORY = 6;

    private final Runnable onCloseCallback;
    private final WidgetShorterSlider[] sliders = new WidgetShorterSlider[7];
    private final BetterScrollBar scrollBar;
    private final Button buttonSave;
    private final Button buttonCancel;
    private final Button buttonReset;
    private final Button buttonCullBlockEntities;
    private final Button buttonCullTrains;
    private final Button buttonCullRailBox;
    private final Button buttonCullCatenary;
    private final Button buttonCullEyeCandy;
    private final Button buttonGenericBounds;
    private final Button buttonCpuOptimization;
    private final Button buttonGpuOptimization;

    private boolean cullBlockEntities;
    private boolean cullTrains;
    private boolean cullRailBox;
    private boolean cullCatenary;
    private boolean cullEyeCandy;
    private boolean genericBounds;
    private boolean cpuOptimization;
    private boolean gpuOptimization;
    private boolean saveFailed;

    private double scrollValue = 1.0D;
    private int scrollOffset;
    private int viewTop;
    private int viewBottom;
    private int panelHeight = MAX_PANEL_HEIGHT;
    private boolean scrollBarActive;

    public IVRConfigScreen(Runnable onCloseCallback) {
        super(Text.translatable("gui.ivr.render_config.title"));
        this.onCloseCallback = onCloseCallback;
        for (int index = SLIDER_BE; index <= SLIDER_EYE_CANDY; index++) {
            sliders[index] = new WidgetShorterSlider(0, 0,
                    IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1,
                    value -> Integer.toString((value + 1) * IVRConfig.DISTANCE_STEP), null);
        }
        sliders[SLIDER_RAYS] = new WidgetShorterSlider(0, 0,
                (IVRConfig.MAX_MAX_OCCLUSION_RAYS - IVRConfig.MIN_MAX_OCCLUSION_RAYS)
                        / IVRConfig.OCCLUSION_RAY_STEP,
                value -> Integer.toString(IVRConfig.MIN_MAX_OCCLUSION_RAYS
                        + value * IVRConfig.OCCLUSION_RAY_STEP), null);
        sliders[SLIDER_MEMORY] = new WidgetShorterSlider(0, 0,
                IVRConfig.MAX_MEMORY_BUDGET_MIB / IVRConfig.MEMORY_BUDGET_STEP_MIB,
                this::memoryBudgetLabel, null);
        scrollBar = new BetterScrollBar(1.0D, value -> {
            scrollValue = value;
            layoutWidgets();
        });
        buttonSave = new BetterButton(BetterButton.Style.ROUNDED, BetterButton.BACKGROUND_CYAN,
                Text.translatable("gui.ivr.render_config.save"), button -> save());
        buttonCancel = new BetterButton(BetterButton.Style.ROUNDED, BetterButton.BACKGROUND_CYAN,
                Text.translatable("gui.ivr.render_config.cancel"), button -> onClose());
        buttonReset = new BetterButton(BetterButton.Style.ROUNDED, BetterButton.BACKGROUND_CYAN,
                Text.translatable("gui.ivr.render_config.defaults"), button -> restoreDefaults());
        buttonCullBlockEntities = toggleButton(button -> toggleBlockEntities());
        buttonCullTrains = toggleButton(button -> toggleTrains());
        buttonCullRailBox = toggleButton(button -> toggleRailBox());
        buttonCullCatenary = toggleButton(button -> toggleCatenary());
        buttonCullEyeCandy = toggleButton(button -> toggleEyeCandy());
        buttonGenericBounds = toggleButton(button -> toggleGenericBounds());
        buttonCpuOptimization = toggleButton(button -> toggleCpuOptimization());
        buttonGpuOptimization = toggleButton(button -> toggleGpuOptimization());
    }

    private Button toggleButton(Button.OnPress press) {
        return new BetterButton(BetterButton.Style.ROUNDED, BetterButton.BACKGROUND_CYAN,
                Text.translatable("gui.ivr.render_config.toggle.on"), press);
    }

    @Override
    protected void init() {
        super.init();
        panelHeight = Math.min(MAX_PANEL_HEIGHT, height - 30);
        for (WidgetShorterSlider slider : sliders) {
            slider.setHeight(20);
            addDrawableChild(slider);
        }
        addDrawableChild(buttonCullBlockEntities);
        addDrawableChild(buttonCullTrains);
        addDrawableChild(buttonCullRailBox);
        addDrawableChild(buttonCullCatenary);
        addDrawableChild(buttonCullEyeCandy);
        addDrawableChild(buttonGenericBounds);
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
        KSDGui.renderBg(matrices, width, height, PANEL_WIDTH, panelHeight);
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - panelHeight / 2;
        drawCenteredString(matrices, Minecraft.getInstance().font, title, width / 2, top + 12, ARGB_WHITE);
        int contentLeft = left + CONTENT_MARGIN;
        drawSection(matrices, "gui.ivr.render_config.section_culling", contentLeft, SECTION_CULLING);
        drawRowLabel(matrices, "block_entity", contentLeft, ROW_BE);
        drawRowLabel(matrices, "train", contentLeft, ROW_TRAIN);
        drawRowLabel(matrices, "rail_box", contentLeft, ROW_RAIL_BOX);
        drawRowLabel(matrices, "catenary", contentLeft, ROW_CATENARY);
        drawRowLabel(matrices, "eye_candy", contentLeft, ROW_EYE_CANDY);
        drawSection(matrices, "gui.ivr.render_config.section_bounds", contentLeft, SECTION_BOUNDS);
        drawString(matrices, Minecraft.getInstance().font,
                Text.translatable("gui.ivr.render_config.generic_bounds.desc1"),
                contentLeft, contentTop() + ROW_DESC_1 - scrollOffset, ARGB_GRAY);
        drawString(matrices, Minecraft.getInstance().font,
                Text.translatable("gui.ivr.render_config.generic_bounds.desc2"),
                contentLeft, contentTop() + ROW_DESC_2 - scrollOffset, ARGB_GRAY);
        drawRowLabel(matrices, "occlusion_rays", contentLeft, ROW_RAYS);
        drawRowLabel(matrices, "memory_budget", contentLeft, ROW_MEMORY);
        drawSection(matrices, "gui.ivr.render_config.section_performance", contentLeft, SECTION_PERFORMANCE);
        if (saveFailed) {
            drawCenteredString(matrices, Minecraft.getInstance().font,
                    Text.translatable("gui.ivr.render_config.save_failed"), width / 2,
                    top + panelHeight - 58, RGB_RED | ARGB_BLACK);
        }
        super.render(matrices, mouseX, mouseY, delta);
        if (scrollBarActive) {
            scrollBar.render(matrices, mouseX, mouseY, delta);
        }
    }

    private void drawSection(PoseStack matrices, String key, int x, int row) {
        int y = contentTop() + row - scrollOffset;
        if (!insideView(y)) return;
        drawString(matrices, Minecraft.getInstance().font, Text.translatable(key), x, y, SECTION_COLOR);
    }

    private void drawRowLabel(PoseStack matrices, String key, int x, int row) {
        int y = contentTop() + row - scrollOffset + 3;
        if (!insideView(y)) return;
        drawString(matrices, Minecraft.getInstance().font,
                Text.translatable("gui.ivr.render_config." + key), x, y, ARGB_WHITE);
    }

    private int contentTop() {
        return height / 2 - panelHeight / 2 + 26;
    }

    private int contentBottom() {
        return height / 2 - panelHeight / 2 + panelHeight - FOOTER_HEIGHT;
    }

    private boolean insideView(int y) {
        return y >= contentTop() - 10 && y <= contentBottom();
    }

    private int maxScroll() {
        return Math.max(0, CONTENT_HEIGHT - (contentBottom() - contentTop()));
    }

    private void layoutWidgets() {
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - panelHeight / 2;
        viewTop = contentTop();
        viewBottom = contentBottom();
        int maxScroll = maxScroll();
        scrollOffset = (int) Math.round((1.0D - scrollValue) * maxScroll);
        int contentLeft = left + CONTENT_MARGIN;
        int labelWidth = 84;
        int toggleWidth = 52;
        int sliderLeft = contentLeft + labelWidth + toggleWidth + 10;
        int sliderWidth = CONTENT_WIDTH - labelWidth - toggleWidth - 10;
        placeToggle(buttonCullBlockEntities, contentLeft + labelWidth, ROW_BE);
        placeToggle(buttonCullTrains, contentLeft + labelWidth, ROW_TRAIN);
        placeToggle(buttonCullRailBox, contentLeft + labelWidth, ROW_RAIL_BOX);
        placeToggle(buttonCullCatenary, contentLeft + labelWidth, ROW_CATENARY);
        placeToggle(buttonCullEyeCandy, contentLeft + labelWidth, ROW_EYE_CANDY);
        placeSlider(sliders[SLIDER_BE], sliderLeft, ROW_BE, sliderWidth, cullBlockEntities);
        placeSlider(sliders[SLIDER_TRAIN], sliderLeft, ROW_TRAIN, sliderWidth, cullTrains);
        placeSlider(sliders[SLIDER_RAIL_BOX], sliderLeft, ROW_RAIL_BOX, sliderWidth, cullRailBox);
        placeSlider(sliders[SLIDER_CATENARY], sliderLeft, ROW_CATENARY, sliderWidth, cullCatenary);
        placeSlider(sliders[SLIDER_EYE_CANDY], sliderLeft, ROW_EYE_CANDY, sliderWidth, cullEyeCandy);
        placeToggle(buttonGenericBounds, contentLeft, ROW_LEARNING);
        buttonGenericBounds.setMessage(Text.translatable(genericBounds
                ? "gui.ivr.render_config.generic_bounds.enabled"
                : "gui.ivr.render_config.generic_bounds.disabled"));
        IDrawing.setPositionAndWidth(buttonGenericBounds, contentLeft, viewTop + ROW_LEARNING - scrollOffset, 200);
        placeSlider(sliders[SLIDER_RAYS], contentLeft + 110, ROW_RAYS, CONTENT_WIDTH - 110, true);
        placeSlider(sliders[SLIDER_MEMORY], contentLeft + 110, ROW_MEMORY, CONTENT_WIDTH - 110, true);
        int halfWidth = (CONTENT_WIDTH - 8) / 2;
        placeToggle(buttonCpuOptimization, contentLeft, ROW_OPTIMIZATIONS);
        IDrawing.setPositionAndWidth(buttonCpuOptimization, contentLeft,
                viewTop + ROW_OPTIMIZATIONS - scrollOffset, halfWidth);
        placeToggle(buttonGpuOptimization, contentLeft + halfWidth + 8, ROW_OPTIMIZATIONS);
        IDrawing.setPositionAndWidth(buttonGpuOptimization, contentLeft + halfWidth + 8,
                viewTop + ROW_OPTIMIZATIONS - scrollOffset, halfWidth);
        int footerY = top + panelHeight - 30;
        IDrawing.setPositionAndWidth(buttonReset, left + CONTENT_MARGIN, footerY, 104);
        IDrawing.setPositionAndWidth(buttonSave, left + 128, footerY, 104);
        IDrawing.setPositionAndWidth(buttonCancel, left + 244, footerY, 104);
        scrollBarActive = maxScroll > 0;
        if (scrollBarActive) {
            scrollBar.setPositionAndSize(left + PANEL_WIDTH - 10, viewTop + 2, SCROLLBAR_WIDTH,
                    Math.max(12, viewBottom - viewTop - 4));
            scrollBar.setValue(scrollValue);
        }
        updateToggleLabels();
    }

    private void placeToggle(Button button, int x, int row) {
        int y = viewTop + row - scrollOffset;
        IDrawing.setPositionAndWidth(button, x, y, 52);
        button.visible = insideView(y);
    }

    private void placeSlider(WidgetShorterSlider slider, int x, int row, int width, boolean enabled) {
        int y = viewTop + row - scrollOffset;
        IDrawing.setPositionAndWidth(slider, x, y, Math.max(40, width));
        slider.visible = enabled && insideView(y);
    }

    private void setValues() {
        sliders[SLIDER_BE].setValue(IVRConfig.blockEntityRD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_TRAIN].setValue(IVRConfig.trainRD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_RAIL_BOX].setValue(IVRConfig.nteRailBoxRD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_CATENARY].setValue(IVRConfig.msdCatenaryRD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_EYE_CANDY].setValue(IVRConfig.eyeCandyRD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_RAYS].setValue((IVRConfig.maxOcclusionRays - IVRConfig.MIN_MAX_OCCLUSION_RAYS)
                / IVRConfig.OCCLUSION_RAY_STEP);
        sliders[SLIDER_MEMORY].setValue(IVRConfig.memoryBudgetMiB / IVRConfig.MEMORY_BUDGET_STEP_MIB);
        cullBlockEntities = IVRConfig.cullingBlockEntities;
        cullTrains = IVRConfig.cullingTrains;
        cullRailBox = IVRConfig.cullingRailBox;
        cullCatenary = IVRConfig.cullingCatenary;
        cullEyeCandy = IVRConfig.cullingEyeCandy;
        genericBounds = IVRConfig.genericBoundsLearning;
        cpuOptimization = IVRConfig.cpuOptimization;
        gpuOptimization = IVRConfig.gpuOptimization;
        saveFailed = false;
        scrollValue = 1.0D;
        layoutWidgets();
    }

    private void save() {
        try {
            IVRConfig.save(new IVRConfig.Settings(
                    (sliders[SLIDER_BE].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (sliders[SLIDER_TRAIN].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (sliders[SLIDER_CATENARY].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (sliders[SLIDER_RAIL_BOX].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    (sliders[SLIDER_EYE_CANDY].getIntValue() + 1) * IVRConfig.DISTANCE_STEP,
                    cullBlockEntities,
                    cullTrains,
                    cullRailBox,
                    cullCatenary,
                    cullEyeCandy,
                    genericBounds,
                    gpuOptimization,
                    cpuOptimization,
                    sliders[SLIDER_MEMORY].getIntValue() * IVRConfig.MEMORY_BUDGET_STEP_MIB,
                    IVRConfig.MIN_MAX_OCCLUSION_RAYS
                            + sliders[SLIDER_RAYS].getIntValue() * IVRConfig.OCCLUSION_RAY_STEP));
            onClose();
        } catch (IOException exception) {
            IVR.LOGGER.warn("Could not save IVR render config", exception);
            saveFailed = true;
        }
    }

    private void restoreDefaults() {
        sliders[SLIDER_BE].setValue(IVRConfig.DEFAULT_BE_RD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_TRAIN].setValue(IVRConfig.DEFAULT_TRAIN_RD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_RAIL_BOX].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_CATENARY].setValue(IVRConfig.MAX_DISTANCE / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_EYE_CANDY].setValue(IVRConfig.DEFAULT_EYE_CANDY_RD / IVRConfig.DISTANCE_STEP - 1);
        sliders[SLIDER_RAYS].setValue((IVRConfig.DEFAULT_MAX_OCCLUSION_RAYS
                - IVRConfig.MIN_MAX_OCCLUSION_RAYS) / IVRConfig.OCCLUSION_RAY_STEP);
        sliders[SLIDER_MEMORY].setValue(IVRConfig.DEFAULT_MEMORY_BUDGET_MIB / IVRConfig.MEMORY_BUDGET_STEP_MIB);
        cullBlockEntities = true;
        cullTrains = true;
        cullRailBox = true;
        cullCatenary = true;
        cullEyeCandy = true;
        genericBounds = true;
        cpuOptimization = true;
        gpuOptimization = true;
        saveFailed = false;
        layoutWidgets();
    }

    private void updateToggleLabels() {
        buttonCullBlockEntities.setMessage(toggleLabel(cullBlockEntities));
        buttonCullTrains.setMessage(toggleLabel(cullTrains));
        buttonCullRailBox.setMessage(toggleLabel(cullRailBox));
        buttonCullCatenary.setMessage(toggleLabel(cullCatenary));
        buttonCullEyeCandy.setMessage(toggleLabel(cullEyeCandy));
        buttonGenericBounds.setMessage(Text.translatable(genericBounds
                ? "gui.ivr.render_config.generic_bounds.enabled"
                : "gui.ivr.render_config.generic_bounds.disabled"));
        buttonCpuOptimization.setMessage(Text.translatable(cpuOptimization
                ? "gui.ivr.render_config.cpu_optimization.enabled"
                : "gui.ivr.render_config.cpu_optimization.disabled"));
        buttonGpuOptimization.setMessage(Text.translatable(gpuOptimization
                ? "gui.ivr.render_config.gpu_optimization.enabled"
                : "gui.ivr.render_config.gpu_optimization.disabled"));
    }

    private Component toggleLabel(boolean enabled) {
        return Text.translatable(enabled
                ? "gui.ivr.render_config.toggle.on"
                : "gui.ivr.render_config.toggle.off");
    }

    private String memoryBudgetLabel(int value) {
        return value == 0 ? "auto" : Integer.toString(value * IVRConfig.MEMORY_BUDGET_STEP_MIB);
    }

    private void toggleBlockEntities() {
        cullBlockEntities = !cullBlockEntities;
        layoutWidgets();
    }

    private void toggleTrains() {
        cullTrains = !cullTrains;
        layoutWidgets();
    }

    private void toggleRailBox() {
        cullRailBox = !cullRailBox;
        layoutWidgets();
    }

    private void toggleCatenary() {
        cullCatenary = !cullCatenary;
        layoutWidgets();
    }

    private void toggleEyeCandy() {
        cullEyeCandy = !cullEyeCandy;
        layoutWidgets();
    }

    private void toggleGenericBounds() {
        genericBounds = !genericBounds;
        layoutWidgets();
    }

    private void toggleCpuOptimization() {
        cpuOptimization = !cpuOptimization;
        layoutWidgets();
    }

    private void toggleGpuOptimization() {
        gpuOptimization = !gpuOptimization;
        layoutWidgets();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (scrollBarActive && scrollBar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (scrollBarActive && scrollBar.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollBarActive && scrollBar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (scrollBarActive && mouseY >= viewTop && mouseY <= viewBottom) {
            scrollValue = Math.max(0.0D, Math.min(1.0D, scrollValue + amount * 0.12D));
            layoutWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
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
