package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.utils.RailDataUtilities;
import net.hulan.ksd.utils.RenderUtilities;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public class KCRSingleTicketMachineScreen extends ScreenMapper implements IGui {

    final Set<KSDRoute> routes = new HashSet<>();
    final Set<KSDStation> stations = new HashSet<>();
    private final int balance;
    private final KSDStation current;
    private final KCRSingleTicketMachineRailMap railMap;
    private final KCRSingleTicketMachineLegend legend;
    private static final String RMH_CHI = "路線圖";
    private static final String RMH_ENG = "System Map";
    private static final int RMH_HEADER_HEIGHT = 50;
    private static final int RMH_PADDING = 10;
    private static final float RMH_CHI_WIDTH;
    private static final float RMH_CHI_HEIGHT = 40F;
    private static final float RMH_ENG_WIDTH;
    private static final float RMH_ENG_HEIGHT = 20F;
    private static final int RGB_HEADER_BLUE = 0x004684;
    private static final int LEGEND_WIDTH = 100;

    public KCRSingleTicketMachineScreen(BlockPos machinePos, int balance) {
        super(Text.literal(""));
        this.balance = balance;
        current = KSDRailwayData.getStation(KSDClientData.STATIONS, machinePos);
        railMap = new KCRSingleTicketMachineRailMap(this::onClickedOnDestination, this);
        legend = new KCRSingleTicketMachineLegend();
    }

    protected void init() {
        if (current == null) {
            onClose();
        }
        loadRoutes();
        loadStations();
        int componentHeight = height - RMH_HEADER_HEIGHT;
        legend.setPositionAndSize(0, RMH_HEADER_HEIGHT, LEGEND_WIDTH, componentHeight);
        railMap.setPositionAndSize(LEGEND_WIDTH, RMH_HEADER_HEIGHT, width - LEGEND_WIDTH, componentHeight);
        addWidget(legend);
        addWidget(railMap);
        legend.load(componentHeight, routes);
        railMap.load();
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderMapComponent(matrices, mouseX, mouseY, delta);
        renderHeader(matrices);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void onClickedOnDestination(KSDStation destination) {
        if (minecraft != null) {
            UtilitiesClient.setScreen(minecraft, new SingleTicketProcessingScreen(current, destination, balance, this));
        }
    }

    private void loadRoutes() {
        routes.clear();
        Set<KSDRoute> routeInCurrent = DataUtilities.getNonNullSetFromDataCollection(KSDClientData.DATA_CACHE.stationIdToRoutes.get(current.id));
        routes.addAll(routeInCurrent);
        Set<KSDRoute> routeInSameRailNet = new HashSet<>();
        routeInCurrent.forEach(r -> routeInSameRailNet.addAll(RailDataUtilities.getRoutesInSameRailNet(KSDClientData.DATA_CACHE, r)));
        routes.addAll(routeInSameRailNet);
        routes.removeIf(r -> r.isHidden || (!r.routeType.equals(Utilities.KCR_CLASSICAL) && !r.routeType.equals(Utilities.KCR_MODERN)));
    }

    private void loadStations() {
        stations.clear();
        for (KSDRoute route : routes) {
            stations.addAll(RailDataUtilities.getStationsInRoute(KSDClientData.DATA_CACHE, route));
        }
    }

    private void renderHeader(PoseStack matrices) {
        Gui.fill(matrices, 0, 0, width, RMH_HEADER_HEIGHT, RGB_HEADER_BLUE | ARGB_BLACK);
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        renderUtilities.drawTexture(matrices, new ResourceLocation("ivr", "textures/block/sign/rmh.png"), 0, 0, RMH_HEADER_HEIGHT, RMH_HEADER_HEIGHT);
        renderUtilities.drawText(matrices, RMH_CHI, RMH_HEADER_HEIGHT + RMH_PADDING, 10, RMH_CHI_WIDTH, RMH_CHI_HEIGHT, ARGB_WHITE);
        renderUtilities.drawText(matrices, RMH_ENG, 170, 25, RMH_ENG_WIDTH, RMH_ENG_HEIGHT, ARGB_WHITE);
    }

    private void renderMapComponent(PoseStack matrices, int mouseX, int mouseY, float delta) {
        Gui.fill(matrices, 0, RMH_HEADER_HEIGHT, width, height, ARGB_WHITE);
        railMap.render(matrices, mouseX, mouseY, delta);
        legend.render(matrices, mouseX, mouseY, delta);
    }

    static {
        RMH_CHI_WIDTH = RenderUtilities.getInstance().getTextWidth(RMH_CHI, RMH_CHI_HEIGHT / Minecraft.getInstance().font.lineHeight);
        RMH_ENG_WIDTH = RenderUtilities.getInstance().getTextWidth(RMH_ENG, RMH_ENG_HEIGHT / Minecraft.getInstance().font.lineHeight);
    }
}
