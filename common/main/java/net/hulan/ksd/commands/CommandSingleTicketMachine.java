package net.hulan.ksd.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.hulan.ksd.data.KCRSingleTicketSystem;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class CommandSingleTicketMachine {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("ivr")
                .then(Commands.literal("single_ticket_machine")
                        .then(Commands.literal("MTR").executes(context -> {
                            openScreen(context, KCRSingleTicketSystem.TicketType.MTR);
                            return 0;
                        }))
                        .then(Commands.literal("KCR").executes(context -> {
                            openScreen(context, KCRSingleTicketSystem.TicketType.KCR);
                            return 0;
                        }))
                        .then(Commands.literal("LRT").executes(context -> {
                            openScreen(context, KCRSingleTicketSystem.TicketType.LRT);
                            return 0;
                        })));
    }

    private static void openScreen(CommandContext<CommandSourceStack> context, KCRSingleTicketSystem.TicketType ticketType) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer player) {
            KSDPacketServer.openSingleTicketMachineScreenS2C(player, ticketType, player.blockPosition());
        }
    }
}
