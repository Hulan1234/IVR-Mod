package net.hulan.ksd.utils;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.data.TicketSystem;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.hulan.ksd.packet.KSDPacket;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class Utilities_1_18_2 extends Utilities {

    public void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(Commands.literal("open").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            BlockPos machinePos = player.blockPosition();
            int balance = TicketSystem.getPlayerScore(player.getLevel(), player, "mtr_balance").getScore();
            KSDPacketServer.openKCRTicketMachineScreenS2C(player, machinePos, balance);
            return 0;
        })));
    }
}
