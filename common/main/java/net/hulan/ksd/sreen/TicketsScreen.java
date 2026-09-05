package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KCRSingleTicketSystem;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

public class TicketsScreen extends ScreenMapper implements KSDGui {

    private final Button buttonOpenMTRSTMScreen;
    private final Button buttonOpenKCRSTMScreen;
    private final Button buttonOpenLRTSTMScreen;
    private final Button buttonOpenITTTMScreen;
    private final Button buttonOpenApplyOctopusScreen;
    private final Button buttonOpenAddValueScreen;
    private final int panelWidth;
    private final int panelHeight;
    private static final int MIN_PANEL_WIDTH = BUTTON_WIDTH * 2 + BUTTON_GAP + 8;
    private static final int MIN_PANEL_HEIGHT = BUTTON_HEIGHT * 3 + BUTTON_GAP * 2 + 40;

    public TicketsScreen(BlockPos storeBlockPos, int mtrBalance) {
        super(Text.literal("Tickets"));
        this.panelWidth = Math.max(MIN_PANEL_WIDTH, 440);
        this.panelHeight = Math.max(MIN_PANEL_HEIGHT, 290);
        buttonOpenMTRSTMScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_stm_screen_mtr"), button ->
                openSTMScreen(KCRSingleTicketSystem.TicketType.MTR, storeBlockPos, mtrBalance));
        buttonOpenKCRSTMScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_stm_screen_kcr"), button ->
                openSTMScreen(KCRSingleTicketSystem.TicketType.KCR, storeBlockPos, mtrBalance));
        buttonOpenLRTSTMScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_stm_screen_lrt"), button ->
                openSTMScreen(KCRSingleTicketSystem.TicketType.LRT, storeBlockPos, mtrBalance));
        buttonOpenITTTMScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_stm_screen_itt"), button -> {
        });
        buttonOpenApplyOctopusScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_apply_octopus_screen"), button ->
                openApplyOctopusScreen(mtrBalance));
        buttonOpenAddValueScreen = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.translatable("gui.ksd.open_add_value_screen"), button ->
                openAddValueScreen(storeBlockPos, mtrBalance));
    }

    protected void init() {
        super.init();
        final int panelLeft = width / 2 - panelWidth / 2;
        final int panelTop = height / 2 - panelHeight / 2;
        final int left = panelLeft + (panelWidth - BUTTON_WIDTH * 2 - BUTTON_GAP) / 2;
        final int top = panelTop + (panelHeight - BUTTON_HEIGHT * 3 - BUTTON_GAP * 2) / 2;
        final Button[] buttons = {
                buttonOpenMTRSTMScreen,
                buttonOpenKCRSTMScreen,
                buttonOpenLRTSTMScreen,
                buttonOpenITTTMScreen,
                buttonOpenApplyOctopusScreen,
                buttonOpenAddValueScreen
        };
        for (int i = 0; i < buttons.length; i++) {
            final int column = i % 2;
            final int row = i / 2;
            final Button button = buttons[i];
            IDrawing.setPositionAndWidth(button, left + column * (BUTTON_WIDTH + BUTTON_GAP), top + row * (BUTTON_HEIGHT + BUTTON_GAP), BUTTON_WIDTH);
            addDrawableChild(button);
        }
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        PaymentScreen.renderPaymentBackground(matrices, width, height, panelWidth, panelHeight);
        drawCenteredString(matrices, Minecraft.getInstance().font, title, width / 2, height / 2 - panelHeight / 2 + 7, IGui.ARGB_WHITE);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean isPauseScreen() {
        return false;
    }

    public static void openSTMScreen(KCRSingleTicketSystem.TicketType ticketType, BlockPos storeBlockPos, int mtrBalance) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player != null) {
            KSDStation current = KSDRailwayData.getStation(KSDClientData.STATIONS, storeBlockPos);
            if (!(minecraft.screen instanceof KCRSTMachineScreen) && current != null) {
                UtilitiesClient.setScreen(minecraft, new KCRSTMachineScreen(ticketType, current, storeBlockPos, mtrBalance));
            }
        }
    }

    public static void openITTTMScreen(int mtrBalance) {
        //TODO 直通车售票界面
    }

    public static void openSTFAScreen(int mtrBalance) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player != null) {
            BlockPos storeBlockPos = player.blockPosition();
            if (!(minecraft.screen instanceof STFareAdjustmentScreen)) {
                UtilitiesClient.setScreen(minecraft, new PutItemScreen(
                        "item.ksd.single_ticket",
                        KSDItems.SINGLE_TICKET.get(),
                        PutItemScreen.PutMethod.PUT,
                        true,
                        (singleTicketItem, amount) -> {
                            CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
                            KSDStation current = DataUtilities.getStation(KSDClientData.STATIONS, singleTicketTag.getLong("entered_station_id"));
                            KSDStation destination = KSDRailwayData.getStation(KSDClientData.STATIONS, storeBlockPos);
                            if (!(minecraft.screen instanceof STFareAdjustmentScreen) &&
                                    current != null &&
                                    destination != null) {
                                UtilitiesClient.setScreen(minecraft, new STFareAdjustmentScreen(
                                        current,
                                        destination,
                                        mtrBalance,
                                        singleTicketItem,
                                        storeBlockPos));
                            }
                        }));
            }
        }
    }

    public static void openApplyOctopusScreen(int mtrBalance) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player =  minecraft.player;
        if (player != null) {
            BlockPos storeBlockPos = player.blockPosition();
            if (!(minecraft.screen instanceof ApplyOctopusScreen)) {
                UtilitiesClient.setScreen(minecraft, new ApplyOctopusScreen(mtrBalance, storeBlockPos));
            }
        }
    }

    public static void openAddValueScreen(BlockPos storeBlockPos, int mtrBalance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof KCRSTMachineScreen)) {
            UtilitiesClient.setScreen(minecraft, new PutItemScreen(
                    "item.ksd.octopus",
                    KSDItems.OCTOPUS.get(),
                    PutItemScreen.PutMethod.INSERT,
                    true,
                    (octopusItem, amount) -> {
                        if (!(minecraft.screen instanceof AddValueMachineScreen)) {
                            UtilitiesClient.setScreen(minecraft, new AddValueMachineScreen(
                                    mtrBalance,
                                    octopusItem,
                                    storeBlockPos));
                        }
                    }));
        }
    }
}
