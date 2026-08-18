package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import mtr.screen.*;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.utils.RailDataUtilities;
import net.hulan.ksd.utils.Utilities;
import net.hulan.ksd.packet.KSDPacket;
import net.hulan.ksd.packet.KSDPacketClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Set;

public class EditKSDRouteScreen extends EditNameColorScreenBase<KSDRoute> implements IGui, IPacket {

    private RouteType routeType;
    private long recommendedInterchangeStationId;
    private boolean isSelectingRIS;
    private final Component lightRailRouteNumberText = Text.translatable("gui.mtr.light_rail_route_number");
    private final Component fcCarNumberText = Text.translatable("gui.ksd.fc_car_number");
    private final WidgetBetterTextField textFieldLightRailRouteNumber;
    private final WidgetBetterTextField textFieldFCCar;
    private final Button buttonRouteType;
    private final WidgetBetterCheckbox buttonIsLightRailRoute;
    private final WidgetBetterCheckbox buttonIsRouteHidden;
    private final WidgetBetterCheckbox buttonDisableNextStationAnnouncements;
    private final WidgetBetterCheckbox buttonIsClockwiseRoute;
    private final WidgetBetterCheckbox buttonIsAntiClockwiseRoute;
    private final WidgetBetterCheckbox buttonHasFCService;
    private final Button buttonSelectRIS;
    private final DashboardList stationList;
    private final boolean isCircular;
    private final KSDDashboardScreen dashboardScreen;
    private static final int CHECKBOX_WIDTH = 160;

    public EditKSDRouteScreen(KSDRoute route, KSDDashboardScreen dashboardScreen) {
        super(route, null, "gui.mtr.route_name", "gui.mtr.route_color");
        this.dashboardScreen = dashboardScreen;
        textFieldLightRailRouteNumber = new WidgetBetterTextField("");
        textFieldFCCar = new WidgetBetterTextField("");
        buttonRouteType = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_value"), button -> setRouteTypeText(data.transportMode, routeType.next()));
        buttonIsLightRailRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_light_rail_route"), this::setIsLightRailRoute);
        buttonIsRouteHidden = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_route_hidden"), this::setIsRouteHidden);
        buttonDisableNextStationAnnouncements = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.disable_next_station_announcements"), this::setDisableNextStationAnnouncements);
        buttonIsClockwiseRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_clockwise_route"), this::setIsClockwise);
        buttonIsAntiClockwiseRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_anticlockwise_route"), this::setIsAntiClockwise);
        buttonHasFCService = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.has_fc_service"), this::setHasFCService);
        buttonSelectRIS = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_value"), button -> setIsSelectingRIS(true));
        stationList = new DashboardList(
                null,
                null,
                null,
                null,
                this::onAdd,
                null,
                null,
                () -> ClientData.DASHBOARD_SEARCH,
                text -> ClientData.DASHBOARD_SEARCH = text);
        if (!route.platformIds.isEmpty()) {
            final KSDStation firstStation = KSDClientData.DATA_CACHE.platformIdToStation.get(route.getFirstPlatformId());
            final KSDStation lastStation = KSDClientData.DATA_CACHE.platformIdToStation.get(route.getLastPlatformId());
            isCircular = firstStation != null && lastStation != null && firstStation.id == lastStation.id;
        } else {
            isCircular = false;
        }
    }

    protected void init() {
        setPositionsAndInit(SQUARE_SIZE, width / 4 * 3 - SQUARE_SIZE, width - SQUARE_SIZE);
        IDrawing.setPositionAndWidth(buttonRouteType, SQUARE_SIZE, SQUARE_SIZE * 3, CHECKBOX_WIDTH);
        setRouteTypeText(data.transportMode, data.routeType);
        IDrawing.setPositionAndWidth(buttonIsRouteHidden, SQUARE_SIZE, SQUARE_SIZE * 4, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(buttonDisableNextStationAnnouncements, SQUARE_SIZE, SQUARE_SIZE * 5, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(buttonIsLightRailRoute, SQUARE_SIZE, SQUARE_SIZE * 6, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(textFieldLightRailRouteNumber, SQUARE_SIZE + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 8 + TEXT_FIELD_PADDING / 2, CHECKBOX_WIDTH - TEXT_FIELD_PADDING);
        textFieldLightRailRouteNumber.setValue(data.lightRailRouteNumber);
        IDrawing.setPositionAndWidth(buttonIsClockwiseRoute, SQUARE_SIZE, SQUARE_SIZE * 9 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(buttonIsAntiClockwiseRoute, SQUARE_SIZE, SQUARE_SIZE * 10 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(buttonHasFCService, width / 2 + SQUARE_SIZE, SQUARE_SIZE * 3 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(textFieldFCCar, width / 2 + SQUARE_SIZE + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 5 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
        IDrawing.setPositionAndWidth(buttonSelectRIS, width / 2 + SQUARE_SIZE, SQUARE_SIZE * 7 + TEXT_FIELD_PADDING, 300);
        textFieldFCCar.setValue(String.valueOf(data.firstClassCar + 1));
        stationList.y = SQUARE_SIZE * 8 + TEXT_FIELD_PADDING;
        stationList.height = height - SQUARE_SIZE * 8 + TEXT_FIELD_PADDING;
        stationList.width = width / 2 - SQUARE_SIZE;
        stationList.init(this::addDrawableChild);
        if (data.transportMode.hasRouteTypeVariation) {
            addDrawableChild(buttonRouteType);
        }
        addDrawableChild(textFieldLightRailRouteNumber);
        addDrawableChild(buttonIsLightRailRoute);
        addDrawableChild(buttonIsRouteHidden);
        addDrawableChild(buttonDisableNextStationAnnouncements);
        if (isCircular) {
            addDrawableChild(buttonIsClockwiseRoute);
            addDrawableChild(buttonIsAntiClockwiseRoute);
        }
        addDrawableChild(buttonHasFCService);
        addDrawableChild(textFieldFCCar);
        addDrawableChild(buttonSelectRIS);
        setIsLightRailRoute(data.isLightRailRoute);
        setIsRouteHidden(data.isHidden);
        setDisableNextStationAnnouncements(data.disableNextStationAnnouncements);
        setIsClockwise(data.circularState == Route.CircularState.CLOCKWISE);
        setIsAntiClockwise(data.circularState == Route.CircularState.ANTICLOCKWISE);
        setHasFCService(data.hasFirstClassService && data.transportMode.equals(TransportMode.TRAIN));
        setSelectedRISId(data.recommendedInterchangeStationId);
        setIsSelectingRIS(false);
    }

    public void tick() {
        super.tick();
        stationList.tick();
        textFieldLightRailRouteNumber.tick();
        textFieldFCCar.tick();
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        try {
            renderBackground(matrices);
            renderTextFields(matrices);
            if (textFieldLightRailRouteNumber.visible) {
                drawString(matrices, font, lightRailRouteNumberText, SQUARE_SIZE, SQUARE_SIZE * 7 + TEXT_PADDING, ARGB_WHITE);
            }
            if (routeType.equals(Utilities.KCR_CLASSICAL) && buttonHasFCService.selected()) {
                drawString(matrices, font, fcCarNumberText, width / 2 + SQUARE_SIZE, SQUARE_SIZE * 4 + TEXT_PADDING, ARGB_WHITE);
            }
            if (isSelectingRIS) {
                stationList.render(matrices, Minecraft.getInstance().font);
            }
            super.render(matrices, mouseX, mouseY, delta);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void mouseMoved(double mouseX, double mouseY) {
        stationList.mouseMoved(mouseX, mouseY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        stationList.mouseScrolled(mouseX, mouseY, amount);
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
            UtilitiesClient.setScreen(minecraft, dashboardScreen);
        }
        saveData();
    }

    protected void saveData() {
        super.saveData();
        data.routeType = routeType;
        data.isLightRailRoute = buttonIsLightRailRoute.selected();
        data.lightRailRouteNumber = textFieldLightRailRouteNumber.getValue();
        data.isHidden = buttonIsRouteHidden.selected();
        data.disableNextStationAnnouncements = buttonDisableNextStationAnnouncements.selected();
        if (isCircular) {
            data.circularState = buttonIsClockwiseRoute.selected() ? Route.CircularState.CLOCKWISE : buttonIsAntiClockwiseRoute.selected() ? Route.CircularState.ANTICLOCKWISE : Route.CircularState.NONE;
        } else {
            data.circularState = Route.CircularState.NONE;
        }
        data.hasFirstClassService = buttonHasFCService.selected();
        try {
            data.firstClassCar = Integer.parseInt(textFieldFCCar.getValue()) - 1;
        } catch (NumberFormatException e) {
            data.firstClassCar = -1;
        }
        data.recommendedInterchangeStationId = recommendedInterchangeStationId;
        data.setExtraData(packet -> KSDPacketClient.sendUpdate(KSDPacket.KSD_PACKET_UPDATE_ROUTE, packet));
        data.setFirstClassData(packet -> KSDPacketClient.sendUpdate(KSDPacket.KSD_PACKET_UPDATE_ROUTE, packet));
        DataUtilities.executeFromDataSet(ClientData.ROUTES, r -> r.id == data.id, mtrRoute -> {
            mtrRoute.name = data.name;
            mtrRoute.color = data.color;
            mtrRoute.routeType = data.routeType;
            mtrRoute.isLightRailRoute = data.isLightRailRoute;
            mtrRoute.lightRailRouteNumber = data.lightRailRouteNumber;
            mtrRoute.isHidden = data.isHidden;
            mtrRoute.disableNextStationAnnouncements = data.disableNextStationAnnouncements;
            mtrRoute.circularState = data.circularState;
            mtrRoute.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
        });
    }

    private void onAdd(NameColorDataBase data, int index) {
        if (data instanceof KSDStation) {
            setSelectedRISId(data.id);
            setIsSelectingRIS(false);
        }
    }

    private void setRouteTypeText(TransportMode transportMode, RouteType newRouteType) {
        routeType = newRouteType;
        buttonRouteType.setMessage(Text.translatable(String.format("gui.mtr.route_type_%s_%s", transportMode, routeType).toLowerCase(Locale.ENGLISH)));
        setShowFCService(routeType.equals(Utilities.KCR_CLASSICAL));
    }

    private void setIsLightRailRoute(boolean isLightRailRoute) {
        buttonIsLightRailRoute.setChecked(isLightRailRoute);
        textFieldLightRailRouteNumber.visible = isLightRailRoute;
    }

    private void setIsRouteHidden(boolean isRouteHidden) {
        buttonIsRouteHidden.setChecked(isRouteHidden);
    }

    private void setDisableNextStationAnnouncements(boolean hasNextStationAnnouncements) {
        buttonDisableNextStationAnnouncements.setChecked(hasNextStationAnnouncements);
    }

    private void setIsClockwise(boolean isClockwise) {
        buttonIsClockwiseRoute.setChecked(isClockwise);
        if (isClockwise) {
            buttonIsAntiClockwiseRoute.setChecked(false);
        }
    }

    private void setIsAntiClockwise(boolean isAntiClockwise) {
        buttonIsAntiClockwiseRoute.setChecked(isAntiClockwise);
        if (isAntiClockwise) {
            buttonIsClockwiseRoute.setChecked(false);
        }
    }

    private void setShowFCService(boolean show) {
        buttonHasFCService.visible = show;
        textFieldFCCar.visible = show && buttonHasFCService.selected();
        buttonSelectRIS.visible = show && buttonHasFCService.selected();
    }

    private void setHasFCService(boolean hasFCService) {
        buttonHasFCService.setChecked(hasFCService);
        textFieldFCCar.visible = hasFCService;
        buttonSelectRIS.visible = hasFCService;
    }

    private void setSelectedRISId(long risId) {
        if (risId == 0) {
            KSDStation firstStation = KSDClientData.DATA_CACHE.platformIdToStation.get(data.getFirstPlatformId());
            if (firstStation != null) {
                risId = firstStation.id;
            }
        }
        recommendedInterchangeStationId = risId;
        KSDStation ris = DataUtilities.getStation(KSDClientData.STATIONS,  recommendedInterchangeStationId);
        if (recommendedInterchangeStationId != 0 && ris != null) {
            buttonSelectRIS.setMessage(Text.translatable("gui.ksd.selected_ris", RailDataUtilities.getMainName(ris)));
        } else {
            buttonSelectRIS.setMessage(Text.translatable("gui.ksd.selected_ris", Text.translatable("gui.ksd.none")));
        }
    }

    private void setIsSelectingRIS(boolean isSelectingRIS) {
        this.isSelectingRIS =  isSelectingRIS;
        stationList.x = isSelectingRIS ? width / 2 + SQUARE_SIZE : width;
        if (isSelectingRIS) {
            Set<KSDStation> stations = DataUtilities.getMappedAndNonNullSetFromDataCollection(data.platformIds,
                    rp -> KSDClientData.DATA_CACHE.platformIdToStation.get(rp.platformId));
            stationList.setData(stations, false, false, false, false, true, false);
        }
    }
}
