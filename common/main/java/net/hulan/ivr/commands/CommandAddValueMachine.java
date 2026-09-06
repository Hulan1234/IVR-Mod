package net.hulan.ivr.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class CommandAddValueMachine {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("ivr")
                .then(Commands.literal("add_value_machine").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Entity entity = source.getEntity();
                            if (entity instanceof ServerPlayer player) {
                                KSDPacketServer.openAddValueMachine(player, player.blockPosition());
                            }
                            return 0;
                        }));
    }
}
