package net.hulan.ivr.block;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public interface StorableBlockEntity {

    void storeItems(List<ItemStack> items);

    void releaseItems(Inventory inventory);
}
