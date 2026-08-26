package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import mtr.screen.WidgetBetterCheckbox;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KCRTicketSystem;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.Payment;
import net.hulan.ksd.data.WayFinder;
import net.hulan.ksd.packet.KSDPacketClient;
import net.hulan.ksd.utils.RailDataUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SingleTicketProcessingScreen extends ScreenMapper implements IGui {

    private Payment payment;
    private int totalFare;
    private int amount;
    private boolean failed;
    private MutableComponent failedMessage;
    private int fare;
    private final int mtrBalance;
    private final KSDStation current;
    private final KSDStation destination;
    private final KCRSingleTicketMachineScreen ticketMachineScreen;
    private final Button buttonPayment;
    private final WidgetBetterCheckbox buttonIsConcessionary;
    private final WidgetBetterCheckbox buttonFCAvailable;
    private final Button buttonConfirm;
    private final Button buttonCancel;
    private final List<Button> amountButtons = new ArrayList<>(10);
    private static final int PADDING = 50;
    private static final int RGB_RED = 0xFF0000;

    protected SingleTicketProcessingScreen(@NotNull KSDStation current, @NotNull KSDStation destination, int mtrBalance, KCRSingleTicketMachineScreen ticketMachineScreen) {
        super(Text.literal(""));
        this.current = current;
        this.destination = destination;
        this.ticketMachineScreen = ticketMachineScreen;
        buttonPayment = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_value"), button -> setPayment(payment.next()));
        buttonIsConcessionary = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.is_concessionary"), this::setIsConcessionary);
        buttonFCAvailable = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.ksd.fc_available"), this::setFCAvailable);
        buttonConfirm = UtilitiesClient.newButton(Text.translatable("gui.ksd.confirm"), button -> process(true));
        buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.ksd.cancel"), button -> process(false));
        for (int i = 0; i < 10; i++) {
            final int amount = i + 1;
            amountButtons.add(UtilitiesClient.newButton(Text.literal(String.valueOf(amount)), button -> setAmount(amount)));
        }
        this.mtrBalance = mtrBalance;
    }

    protected void init() {
        IDrawing.setPositionAndWidth(buttonPayment,width / 2 - 100, height / 2 + 20, 200);
        IDrawing.setPositionAndWidth(buttonIsConcessionary,width / 2 - 100, height / 2 - 60, 100);
        IDrawing.setPositionAndWidth(buttonFCAvailable,width / 2, height / 2 - 60, 100);
        IDrawing.setPositionAndWidth(buttonConfirm, width / 2 - PADDING - 100, height / 2 + 100, 100);
        IDrawing.setPositionAndWidth(buttonCancel, width / 2 + PADDING, height / 2 + 100, 100);
        for (int i = 0; i < 10; i++) {
            Button button = amountButtons.get(i);
            IDrawing.setPositionAndWidth(button, width / 2 + (i - 5) * 20, height / 2, 20);
            addDrawableChild(button);
        }
        addDrawableChild(buttonPayment);
        addDrawableChild(buttonIsConcessionary);
        addDrawableChild(buttonFCAvailable);
        addDrawableChild(buttonConfirm);
        addDrawableChild(buttonCancel);
        setAmount(1);
        setPayment(Payment.EMERALDS);
        setIsConcessionary(false);
    }

    public void tick() {
        for (int i = 0; i < 10; i++) {
            amountButtons.get(i).active = amount != i + 1;
        }
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        // Use the same restrained inventory-panel background as PutItemScreen without changing payment logic.
        Gui.fill(matrices, width / 2 - 220, height / 2 - 145, width / 2 + 220, height / 2 + 145, 0xFFC6C6C6);
        Gui.fill(matrices, width / 2 - 216, height / 2 - 141, width / 2 + 216, height / 2 + 141, 0xFF4A4A4A);
        drawCenteredString(matrices, Minecraft.getInstance().font, getCurrentText(), width / 2, height / 2 - 120, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getDestinationText(), width / 2, height / 2 - 100, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getFareText(), width / 2, height / 2 - 80, ARGB_WHITE);
        if (buttonFCAvailable.selected()) {
            drawCenteredString(matrices, Minecraft.getInstance().font, getWarningMessage(), width / 2, height / 2 - 40, RGB_RED | ARGB_BLACK);
        }
        drawCenteredString(matrices, Minecraft.getInstance().font, getAmountText(), width / 2, height / 2 - 20, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getTotalFareText(), width / 2, height / 2 + 40, ARGB_WHITE);
        drawCenteredString(matrices, Minecraft.getInstance().font, getPayingText(), width / 2, height / 2 + 60, ARGB_WHITE);
        if (failed) {
            drawCenteredString(matrices, Minecraft.getInstance().font, getFailedText(), width / 2, height / 2 + 80, RGB_RED | ARGB_BLACK);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean isPauseScreen() {
        return false;
    }

    private void setPayment(Payment payment) {
        this.payment = payment;
        buttonPayment.setMessage(Text.translatable(String.format("gui.ksd.payment_%s", payment).toLowerCase()));
        failed = false;
    }

    private void setAmount(int amount) {
        this.amount = Mth.clamp(amount, 1, 10);
        calculateFare();
    }

    private void setIsConcessionary(boolean isConcessionary) {
        buttonIsConcessionary.setChecked(isConcessionary);
        calculateFare();
    }

    private void setFCAvailable(boolean firstClassAvailable) {
        buttonFCAvailable.setChecked(firstClassAvailable);
        calculateFare();
    }

    private void calculateFare() {
        WayFinder wayFinder = KSDClientData.DATA_CACHE.wayFinder;
        fare = KCRTicketSystem.getFare(wayFinder, current, destination, buttonIsConcessionary.selected(), buttonFCAvailable.selected());
        totalFare = fare * amount;
        failed = false;
    }

    private MutableComponent getCurrentText() {
        String[] currentMain = RailDataUtilities.getSplitName(current);
        return Text.translatable("gui.ksd.current", isEnglish() && currentMain.length >= 2 ? currentMain[1] : currentMain[0]);
    }

    private MutableComponent getDestinationText() {
        String[] destinationMain = RailDataUtilities.getSplitName(destination);
        return Text.translatable("gui.ksd.destination", isEnglish() && destinationMain.length >= 2 ? destinationMain[1] : destinationMain[0]);
    }

    private MutableComponent getFareText() {
        return Text.translatable("gui.ksd.fare", String.valueOf(fare));
    }

    private MutableComponent getWarningMessage() {
        return Text.translatable("gui.ksd.st_fc_warning");
    }

    private MutableComponent getAmountText() {
        return Text.translatable("gui.ksd.amount");
    }

    private MutableComponent getTotalFareText() {
        return Text.translatable("gui.ksd.total", String.valueOf(totalFare));
    }

    private MutableComponent getPayingText() {
        return Text.translatable("gui.ksd.paying", switch (payment) {
            case EMERALDS -> Text.translatable("gui.ksd.emeralds", (int) Math.ceil((double) totalFare / 16)).getString();
            case MTR_BALANCE, OCTOPUS -> Text.translatable("gui.ksd.hkd", totalFare).getString();
        });
    }

    private MutableComponent getFailedText() {
        return Text.translatable("gui.ksd.failed", failedMessage);
    }

    private void process(boolean confirm) {
        if (confirm) {
            int payCount = totalFare;
            switch (payment) {
                case EMERALDS -> {
                    payCount = KCRTicketSystem.getEmeraldCount(payCount);
                    if (minecraft != null) {
                        UtilitiesClient.setScreen(minecraft, new PutItemScreen(
                                "item.minecraft.emerald",
                                Items.EMERALD,
                                payCount,
                                PutItemScreen.PutMethod.PUT,
                                true,
                                null,
                                (itemStack, count) -> KSDPacketClient.sendTicketProcessingDataC2S(
                                        ticketMachineScreen.machinePos,
                                        payment,
                                        fare,
                                        amount,
                                        count,
                                        buttonIsConcessionary.selected(),
                                        buttonFCAvailable.selected())));
                    }
                }
                case MTR_BALANCE -> {
                    if (mtrBalance < payCount) {
                        failed = true;
                        failedMessage = Text.translatable("gui.ksd.insufficient_mtr_balance");
                    } else {
                        failed = false;
                    }
                    if (!failed) {
                        KSDPacketClient.sendTicketProcessingDataC2S(ticketMachineScreen.machinePos, payment, fare, amount, payCount, buttonIsConcessionary.selected(), buttonFCAvailable.selected());
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
                UtilitiesClient.setScreen(minecraft, ticketMachineScreen);
            }
        }
    }

    public static boolean isEnglish() {
        return Minecraft.getInstance().options.languageCode.startsWith("en");
    }
}
