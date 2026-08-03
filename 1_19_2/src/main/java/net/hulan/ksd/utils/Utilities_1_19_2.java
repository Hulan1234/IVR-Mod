package net.hulan.ksd.utils;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

public class Utilities_1_19_2 extends Utilities {

    public void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ct, selection) -> dispatcher.register(Commands.literal("/test").executes(context -> {
            context.getSource().sendSuccess((Component) Commands.literal("1"), true);
            return -1;
        })));
    }
}
