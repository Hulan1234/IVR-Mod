package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import net.hulan.ksd.data.FirstClassValidationSystem;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.Octopus;
import net.hulan.ksd.packet.KSDPacketClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class FCFareAdjustmentScreen extends PaymentScreen implements IGui {

    public FCFareAdjustmentScreen(int mtrBalance, ScreenMapper parent, BlockPos storeBlockPos) {
        super(mtrBalance, parent, storeBlockPos);
    }

    protected void init() {
        super.init();
        loadTicketData();
    }

    public void tick() {
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
    }

    void countTotal() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            total = FirstClassValidationSystem.hasPunishment(player) ? FirstClassValidationSystem.FC_EVASION_FINE : 0;
        } else {
            total = 0;
        }
        super.countTotal();
    }

    void extraAction() {
        KSDPacketClient.sendAdjustFCFareC2S();
    }

    void payWithOctopus(UUID uuid) {
        KSDPacketClient.sendOctopusAddValueC2S(
                uuid,
                -total,
                Octopus.History.Source.MTR);
        extraAction();
    }

    private void loadTicketData() {
        countTotal();
        failed = false;
    }
}
