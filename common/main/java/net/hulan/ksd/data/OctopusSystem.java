package net.hulan.ksd.data;

import net.hulan.ksd.KSDItems;
import net.hulan.ksd.item.ItemOctopus;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class OctopusSystem {

    public static Octopus createOctopus(int addValue, JSONDataManager jsonDataManager) {
        Octopus octopus = new Octopus();
        octopus.addBalance(addValue, Octopus.History.TransactionType.ADD_VALUE);
        jsonDataManager.octopuses.add(octopus);
        return octopus;
    }

    public static ItemStack createOctopusItem(int addValue, JSONDataManager jsonDataManager) {
        Octopus octopus = createOctopus(addValue, jsonDataManager);
        ItemStack octopusItem = new ItemStack(KSDItems.OCTOPUS.get());
        CompoundTag octopusTag = octopusItem.getOrCreateTag();
        octopusTag.putUUID("uuid", octopus.uuid);
        return octopusItem;
    }

    public static void addValue(ItemStack octopusItem, int addValue, JSONDataManager jsonDataManager, Octopus.History.TransactionType source) {
        CompoundTag octopusTag = octopusItem.getOrCreateTag();
        UUID uuid = octopusTag.getUUID("uuid");
        Octopus octopus = jsonDataManager.getOctopus(uuid);
        if (octopus != null) {
            octopus.addBalance(addValue, source);
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

    public static ItemStack findOctopusItem(ItemStack identifyItem, Inventory inventory) {
        return Utilities.getInstance().findFilteredItem(inventory, item -> {
            CompoundTag itemTag = item.getOrCreateTag();
            CompoundTag identifyItemTag = identifyItem.getOrCreateTag();
            UUID itemUUID = itemTag.getUUID("uuid");
            UUID identifyItemUUID = identifyItemTag.getUUID("uuid");
            return item.getItem() instanceof ItemOctopus && itemUUID.equals(identifyItemUUID);
        });
    }
}
