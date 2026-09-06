package net.hulan.ksd.data;

import net.hulan.ksd.KSDItems;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class OctopusSystem {

    public static final int BASE_PRICE = 99;

    public static Octopus ApplyOctopus(int addValue, boolean isConcessionary, JSONDataManager jsonDataManager) {
        Octopus octopus = new Octopus(isConcessionary);
        octopus.addBalance(addValue, Octopus.History.Source.ADD_VALUE);
        jsonDataManager.octopuses.add(octopus);
        return octopus;
    }

    public static ItemStack ApplyOctopusItem(int addValue, boolean isConcessionary, JSONDataManager jsonDataManager) {
        Octopus octopus = ApplyOctopus(addValue, isConcessionary, jsonDataManager);
        ItemStack octopusItem = new ItemStack(KSDItems.OCTOPUS.get());
        CompoundTag octopusTag = octopusItem.getOrCreateTag();
        octopus.toNBT(octopusTag);
        return octopusItem;
    }

    public static void addValue(UUID uuid, int addValue, Octopus.History.Source source, JSONDataManager jsonDataManager, Inventory inventory) {
        ItemStack octopusItem = findOctopusItem(uuid, inventory);
        if (octopusItem != null) {
            CompoundTag octopusTag = octopusItem.getOrCreateTag();
            Octopus octopus = jsonDataManager.getOctopus(uuid);
            if (octopus != null) {
                octopus.addBalance(addValue, source);
                octopus.toNBT(octopusTag);
            }
        }
    }

    public static String readPrintableData(ItemStack octopusItem, JSONDataManager jsonDataManager) {
        CompoundTag octopusTag = octopusItem.getOrCreateTag();
        UUID uuid = octopusTag.getUUID("uuid");
        Octopus octopus = jsonDataManager.getOctopus(uuid);
        if (octopus != null) {
            return octopus.getPrintedData();
        }
        return "";
    }

    public static ItemStack findOctopusItem(UUID uuid, Inventory inventory) {
        return Utilities.getInstance().findFilteredItem(inventory, item -> {
            if (!(item.getItem() == KSDItems.OCTOPUS.get())) {
                return false;
            }
            CompoundTag itemTag = item.getOrCreateTag();
            UUID itemUUID = itemTag.getUUID("uuid");
            return itemUUID.equals(uuid);
        });
    }
}
