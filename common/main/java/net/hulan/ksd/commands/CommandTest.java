package net.hulan.ksd.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class CommandTest {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("ivr")
                .then(Commands.literal("test").executes(context -> {
                    System.out.println("testing");
                    return 0;
                }));
    }
}
