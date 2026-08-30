package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.screen.WidgetBetterCheckbox;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.*;
import net.hulan.ksd.packet.KSDPacketClient;
import net.hulan.ksd.utils.RailDataUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

public class SingleTicketFareAdjustmentScreen extends ScreenMapper implements IGui {

    private final KSDStation current;
    private final KSDStation destination;
    private final ItemStack singleTicketItem;
    private PaymentMethod paymentMethod;
    private boolean failed;
    private MutableComponent failedMessage;
    private int originFare;
    private int actualFare;
    private int total;
    private boolean expired;
    private int expiredFare;
    private final int mtrBalance;
    private final BlockPos storeBlockPos;
    private final Button buttonPaymentMethod;
    private final WidgetBetterCheckbox buttonIsConcessionary;
    private final WidgetBetterCheckbox buttonFCAvailable;
    private final Button buttonConfirm;
    private final Button buttonCancel;
    private static final int PADDING = 50;
    private static final int RGB_RED = 0xFF0000;

    public SingleTicketFareAdjustmentScreen(@NotNull KSDStation current, @NotNull KSDStation destination, int mtrBalance, ItemStack singleTicketItem, BlockPos storeBlockPos) {
        super(Text.literal(""));
        this.current = current;
        this.destination = destination;
        this.mtrBalance = mtrBalance;
        this.singleTicketItem = singleTicketItem;
        this.storeBlockPos = storeBlockPos;
        buttonPaymentMethod = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_value"), button -> setPaymentMethod(paymentMethod.next()));
        buttonIsConcessionary = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.is_concessionary"), b -> {});
        buttonFCAvailable = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.fc_available"), b -> {});
        buttonConfirm = UtilitiesClient.newButton(Text.translatable("gui.ksd.confirm"), button -> process(true));
        buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.ksd.cancel"), button -> process(false));
    }

    protected void init() {
        IDrawing.setPositionAndWidth(buttonPaymentMethod,width / 2 - 100, height / 2 + 20, 202);
        IDrawing.setPositionAndWidth(buttonIsConcessionary,width / 2 - 100, height / 2 - 40, 100);
        IDrawing.setPositionAndWidth(buttonFCAvailable,width / 2, height / 2 - 40, 100);
        IDrawing.setPositionAndWidth(buttonConfirm, width / 2 - PADDING - 100, height / 2 + 102, 100);
        IDrawing.setPositionAndWidth(buttonCancel, width / 2 + PADDING, height / 2 + 102, 100);
        addDrawableChild(buttonPaymentMethod);
        addDrawableChild(buttonIsConcessionary);
        addDrawableChild(buttonFCAvailable);
        addDrawableChild(buttonConfirm);
        addDrawableChild(buttonCancel);
        loadTicketData();
        setPaymentMethod(PaymentMethod.EMERALDS);
    }

    public void tick() {
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        Gui.fill(matrices, width / 2 - 220, height / 2 - 145, width / 2 + 220, height / 2 + 145, 0xFFC6C6C6);
        Gui.fill(matrices, width / 2 - 216, height / 2 - 141, width / 2 + 216, height / 2 + 141, 0xFF4A4A4A);
        drawCenteredString(matrices, Minecraft.getInstance().font, getCurrentText(), width / 2, height / 2 - 120, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getDestinationText(), width / 2, height / 2 - 100, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getOriginFareText(), width / 2, height / 2 - 80, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getActualFareText(), width / 2, height / 2 - 60, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getExpiredText(), width / 2, height / 2 - 20, ARGB_WHITE);
        if (expired) {
            drawCenteredString(matrices, Minecraft.getInstance().font, getExpiredFareText(), width / 2, height / 2, ARGB_WHITE);
        }
        drawCenteredString(matrices, Minecraft.getInstance().font, getTotalText(), width / 2, height / 2 + 42, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getPayingText(), width / 2, height / 2 + 62, ARGB_WHITE);
        if (failed) {
            drawCenteredString(matrices, Minecraft.getInstance().font, getFailedText(), width / 2, height / 2 + 82, RGB_RED | ARGB_BLACK);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean isPauseScreen() {
        return false;
    }

    private void loadTicketData() {
        WayFinder wayFinder = KSDClientData.DATA_CACHE.wayFinder;
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        int fare = singleTicketTag.getInt("fare");
        long expireTime = singleTicketTag.getLong("expire_time");
        boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
        boolean fcAvailable = singleTicketTag.getBoolean("fc_available");
        originFare = fare;
        actualFare = KCRTicketSystem.getFare(wayFinder, current, destination, isConcessionary, fcAvailable);
        expired = KCRSingleTicketSystem.isExpired(expireTime);
        expiredFare = KCRSingleTicketSystem.getExpiredFare(expireTime);
        total = actualFare - originFare + (expired ? expiredFare : 0);
        setIsConcessionary(isConcessionary);
        setFCAvailable(fcAvailable);
        failed = false;
    }

    private void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
        buttonPaymentMethod.setMessage(Text.translatable(String.format("gui.ksd.payment_method_%s", paymentMethod).toLowerCase()));
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
        return Text.translatable(expired ? "gui.ksd.expired" : "gui.ksd.not_expired");
    }

    private MutableComponent getExpiredFareText() {
        return Text.translatable("gui.ksd.expired_fare", String.valueOf(expiredFare));
    }

    private MutableComponent getTotalText() {
        return Text.translatable("gui.ksd.total", String.valueOf(total));
    }

    private MutableComponent getPayingText() {
        return Text.translatable("gui.ksd.paying", switch (paymentMethod) {
            case EMERALDS -> Text.translatable("gui.ksd.emeralds", (int) Math.ceil((double) originFare / 16)).getString();
            case MTR_BALANCE, OCTOPUS -> Text.translatable("gui.ksd.hkd", originFare).getString();
        });
    }

    private MutableComponent getFailedText() {
        return Text.translatable("gui.ksd.failed", failedMessage);
    }

    private void process(boolean confirm) {
        if (confirm) {
            switch (paymentMethod) {
                case EMERALDS -> {
                    final int emeraldCount = KCRTicketSystem.getEmeraldCount(total);
                    if (minecraft != null) {
                        UtilitiesClient.setScreen(minecraft, new PutItemScreen(
                                "item.minecraft.emerald",
                                Items.EMERALD,
                                emeraldCount,
                                PutItemScreen.PutMethod.PUT,
                                false,
                                (itemStack, count) -> {
                                    KSDPacketClient.sendPaymentC2S(
                                            PaymentMethod.EMERALDS,
                                            emeraldCount,
                                            count,
                                            storeBlockPos);
                                    KSDPacketClient.sendAdjustSingleTicketFareC2S(
                                            singleTicketItem,
                                            actualFare - originFare,
                                            storeBlockPos);
                                    if (minecraft != null) {
                                        UtilitiesClient.setScreen(minecraft, null);
                                    }
                                }));
                    }
                }
                case MTR_BALANCE -> {
                    if (mtrBalance < 0) {
                        failed = true;
                        failedMessage = Text.translatable("gui.ksd.insufficient_mtr_balance");
                    } else {
                        failed = false;
                        KSDPacketClient.sendPaymentC2S(
                                PaymentMethod.EMERALDS,
                                total,
                                total,
                                storeBlockPos);
                        KSDPacketClient.sendAdjustSingleTicketFareC2S(
                                singleTicketItem,
                                actualFare - originFare,
                                storeBlockPos);
                        if (minecraft != null) {
                            UtilitiesClient.setScreen(minecraft, null);
                        }
                    }
                }
                case OCTOPUS -> {
                    failed = true;
                    failedMessage = Text.translatable("gui.ksd.no_octopus");
                }
            }
        } else {
            if (minecraft != null) {
                UtilitiesClient.setScreen(minecraft, null);
            }
        }
    }

    public static boolean isEnglish() {
        return Minecraft.getInstance().options.languageCode.startsWith("en");
    }
}
