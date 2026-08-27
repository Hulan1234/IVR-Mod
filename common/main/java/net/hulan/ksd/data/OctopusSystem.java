package net.hulan.ksd.data;

import net.minecraft.world.item.ItemStack;

public class OctopusSystem {

    public static Octopus createOctopus(int addValue, JSONDataManager jsonDataManager) {
        Octopus octopus = new Octopus();
        octopus.balance += addValue;
        octopus.histories.add(new Octopus.History(octopus.id, addValue, Octopus.History.TransactionType.ADD_VALUE));
        jsonDataManager.octopuses.add(octopus);
        return octopus;
    }
}
