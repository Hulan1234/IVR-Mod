package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.Text;
import mtr.screen.WidgetBetterCheckbox;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.*;
import net.hulan.ksd.packet.KSDPacketClient;
import net.hulan.ksd.utils.RailDataUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class SingleTicketFareAdjustmentScreen extends PaymentScreen implements IGui {

    private final KSDStation current;
    private final KSDStation destination;
    private final ItemStack singleTicketItem;
    private long singleTicketId;
    private int originFare;
    private int actualFare;
    private String ticketTypeText;
    private boolean expired;
    private int expiredFare;
    private final WidgetBetterCheckbox buttonIsConcessionary;
    private final WidgetBetterCheckbox buttonFCAvailable;

    public SingleTicketFareAdjustmentScreen(@NotNull KSDStation current, @NotNull KSDStation destination, int mtrBalance, ItemStack singleTicketItem, BlockPos storeBlockPos) {
        super(mtrBalance, null, storeBlockPos);
        this.current = current;
        this.destination = destination;
        this.singleTicketItem = singleTicketItem;
        buttonIsConcessionary = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.is_concessionary"), b -> {});
        buttonFCAvailable = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.fc_available"), b -> {});
    }

    protected void init() {
        super.init();
        IDrawing.setPositionAndWidth(buttonIsConcessionary,width / 2 - 100, height / 2, 100);
        IDrawing.setPositionAndWidth(buttonFCAvailable,width / 2, height / 2, 100);
        addDrawableChild(buttonIsConcessionary);
        addDrawableChild(buttonFCAvailable);
        loadTicketData();
    }

    public void tick() {
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        drawCenteredString(matrices, Minecraft.getInstance().font, getCurrentText(), width / 2, height / 2 - 120, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getDestinationText(), width / 2, height / 2 - 100, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getOriginFareText(), width / 2, height / 2 - 80, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getActualFareText(), width / 2, height / 2 - 60, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getTicketTypeText(), width / 2, height / 2 - 40, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getExpiredText(), width / 2, height / 2 - 20, ARGB_WHITE);
    }

    @Override
    void countTotal() {
        total = actualFare - originFare + (expired ? expiredFare : 0);
        super.countTotal();
    }

    void extraAction() {
        KSDPacketClient.sendAdjustSingleTicketFareC2S(
                singleTicketId,
                actualFare - originFare,
                storeBlockPos);
    }

    void payWithOctopus(UUID uuid) {
        KSDPacketClient.sendOctopusAddValueC2S(
                uuid,
                -total,
                Octopus.History.Source.MTR);
        extraAction();
    }

    private void loadTicketData() {
        WayFinder wayFinder = KSDClientData.DATA_CACHE.wayFinder;
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        long id = singleTicketTag.getLong("id");
        int fare = singleTicketTag.getInt("fare");
        long expireTime = singleTicketTag.getLong("expire_time");
        String ticketType = singleTicketTag.getString("ticket_type");
        boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
        boolean fcAvailable = singleTicketTag.getBoolean("fc_available");
        singleTicketId = id;
        originFare = fare;
        actualFare = KCRTicketSystem.getFare(wayFinder, current, destination, isConcessionary, fcAvailable);
        ticketTypeText = ticketType;
        expired = KCRTicketSystem.isExpired(expireTime);
        expiredFare = KCRSingleTicketSystem.getExpiredFare(expireTime);
        setIsConcessionary(isConcessionary);
        setFCAvailable(fcAvailable);
        countTotal();
        failed = false;
    }

    private void setIsConcessionary(boolean isConcessionary) {
        buttonIsConcessionary.setChecked(isConcessionary);
        buttonIsConcessionary.active = false;
    }

    private void setFCAvailable(boolean firstClassAvailable) {
        buttonFCAvailable.setChecked(firstClassAvailable);
        buttonFCAvailable.active = false;
    }

    private MutableComponent getCurrentText() {
        String[] currentMain = RailDataUtilities.getSplitName(current);
        return Text.translatable("gui.ksd.current", isEnglish() && currentMain.length >= 2 ? currentMain[1] : currentMain[0]);
    }

    private MutableComponent getDestinationText() {
        String[] destinationMain = RailDataUtilities.getSplitName(destination);
        return Text.translatable("gui.ksd.destination", isEnglish() && destinationMain.length >= 2 ? destinationMain[1] : destinationMain[0]);
    }

    private MutableComponent getOriginFareText() {
        return Text.translatable("gui.ksd.origin_fare", String.valueOf(originFare));
    }

    private MutableComponent getActualFareText() {
        return Text.translatable("gui.ksd.actual_fare", String.valueOf(actualFare));
    }

    private MutableComponent getExpiredText() {
        return expired ? Text.translatable("gui.ksd.expired", String.valueOf(expiredFare)) : Text.translatable("gui.ksd.not_expired");
    }

    private MutableComponent getTicketTypeText() {
        return Text.translatable("gui.ksd.ticket_type", ticketTypeText);
    }
}
