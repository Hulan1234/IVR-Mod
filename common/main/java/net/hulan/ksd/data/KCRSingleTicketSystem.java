package net.hulan.ksd.data;

import net.hulan.ksd.KSDItems;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Random;

public class KCRSingleTicketSystem {

    public static ItemStack createSingleTicketItem(int fare, boolean isConcessionary, boolean fcAvailable) {
        ItemStack singleTicketItem = new ItemStack(KSDItems.SINGLE_TICKET.get());
        long id = new Random().nextLong();
        CompoundTag singleTicketNBT = singleTicketItem.getOrCreateTag();
        singleTicketNBT.putLong("id", id);
        singleTicketNBT.putInt("fare", fare);
        singleTicketNBT.putLong("expire_time", newExpireTime());
        singleTicketNBT.putBoolean("is_concessionary", isConcessionary);
        singleTicketNBT.putBoolean("fc_available", fcAvailable);
        return singleTicketItem;
    }

    public static void adjustSingleTicketFare(ItemStack singleTicketItem, int addValue) {
        CompoundTag singleTicketNBT = singleTicketItem.getOrCreateTag();
        int originalFare = singleTicketNBT.getInt("fare");
        singleTicketNBT.putInt("fare", originalFare + addValue);
        singleTicketNBT.putLong("expire_time", newExpireTime());
    }

    public static long newExpireTime() {
        return System.currentTimeMillis() + Utilities.EXPIRE_TIME;
    }

    public static boolean isExpired(long expiredTime) {
        return System.currentTimeMillis() - expiredTime > 0;
    }

    public static int getExpiredFare(long expireTime) {
        double minutes = (System.currentTimeMillis() - expireTime) / 60_000.0F;
        return (int) Math.ceil(2 * (minutes / 60));
    }

    public static int getEmeraldCount(int count) {
        return (int) Math.ceil((double) count / 16);
    }

    public static ItemStack findSingleTicketItem(ItemStack identifyItem, Inventory inventory) {
        return Utilities.getInstance().findFilteredItem(inventory, item -> {
            CompoundTag itemTag = item.getOrCreateTag();
            CompoundTag identifyItemTag = identifyItem.getOrCreateTag();
            long itemId = itemTag.getLong("id");
            long identifyItemId = identifyItemTag.getLong("id");;
            return item.getItem() instanceof ItemSingleTicket && itemId == identifyItemId;
        });
    }
}
