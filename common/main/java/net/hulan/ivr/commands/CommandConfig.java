package net.hulan.ivr.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.hulan.ivr.packet.IVRPacketTrainDataGuiServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class CommandConfig {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("ivr").then(Commands.literal("config").executes(context -> {
            CommandSourceStack source = context.getSource();
            if (source.getEntity() instanceof ServerPlayer player) {
                IVRPacketTrainDataGuiServer.openIVRConfigScreenS2C(player);
            }
            return 1;
        }));
    }
}
