package net.hulan.ksd.utils;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.hulan.ksd.packet.KSDPacket;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class Utilities_1_18_2 extends Utilities {

    public void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(Commands.literal("open").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
            packet.writeBlockPos(player.blockPosition());
            Registry.sendToPlayer(player, KSDPacket.KSD_PACKET_OPEN_KCR_TICKET_MACHINE_SCREEN, packet);
            return 0;
        })));
    }
}
