package net.hulan.ksd.commands;

import net.hulan.ksd.utils.Utilities;

public class CommandManager {

    public static void registerCommands() {
        Utilities utilities = Utilities.getInstance();
        utilities.registerCommand(CommandTest.register());
    }
}
