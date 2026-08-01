package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.RouteType;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;


public class KCRTicketMachineScreen extends ScreenMapper implements IGui {

    private final KSDStation current;
    private RouteType routeType;
    private final Component routeTypes = Text.translatable("gui.ksd.route_types");
    private final Button classicalButton;
    private final Button modernButton;
    private final KCRTicketMachineRailMap railMap;

    public KCRTicketMachineScreen(BlockPos machinePos) {
        super(Text.literal(""));
        classicalButton = UtilitiesClient.newButton(Text.translatable("gui.ksd.classical"), button -> setRouteType(Utilities.KCR_CLASSICAL));
        modernButton = UtilitiesClient.newButton(Text.translatable("gui.ksd.modern"), button -> setRouteType(Utilities.KCR_MODERN));
        railMap = new KCRTicketMachineRailMap(this::onClickedOnTerminus);
        current = KSDRailwayData.getStation(KSDClientData.STATIONS,  machinePos);
    }

    protected void init() {
        IDrawing.setPositionAndWidth(classicalButton, 0, 20, 100);
        IDrawing.setPositionAndWidth(modernButton, 0, 40, 100);
        railMap.setPositionAndSize(100, 0, width - 100, height);
        addDrawableChild(classicalButton);
        addDrawableChild(modernButton);
        addWidget(railMap);
        setRouteType(Utilities.KCR_CLASSICAL);
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        railMap.render(matrices, mouseX, mouseY, delta);
        vLine(matrices, 100, 0, height, ARGB_WHITE);
        drawString(matrices, font, routeTypes, TEXT_PADDING, TEXT_PADDING, ARGB_WHITE);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void onClickedOnTerminus(KSDStation terminus) {
        if (current != null) {
            System.out.println(1);
        }
    }

    private void setRouteType(RouteType routeType) {
        this.routeType = routeType;
        railMap.setRouteType(routeType);
        toggleButtons();
    }

    private void toggleButtons() {
        classicalButton.active = !routeType.equals(Utilities.KCR_CLASSICAL);
        modernButton.active = !routeType.equals(Utilities.KCR_MODERN);
    }
}
