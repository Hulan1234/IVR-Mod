package net.hulan.ivr.commands;

import net.hulan.ksd.utils.Utilities;

public class CommandManager {

    public static void registerCommands() {
        Utilities utilities = Utilities.getInstance();
        utilities.registerCommand(CommandTickets.register());
        utilities.registerCommand(CommandAdjustFare.register());
        utilities.registerCommand(CommandPurchaseOctopus.register());
        utilities.registerCommand(CommandAddValueMachine.register());
    }
}
