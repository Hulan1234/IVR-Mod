package net.hulan.ksd.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class CommandAdjustFare {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("ivr")
                .then(Commands.literal("adjust_fare").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    Entity entity = source.getEntity();
                    if (entity instanceof ServerPlayer player) {
                        KSDPacketServer.openSTAdjustmentScreenS2C(player);
                    }
                    return 0;
                }));
    }
}
