package net.hulan.ksd.data;

import mtr.mappings.Text;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Random;

public class KCRSingleTicketSystem {

    public static ItemStack createSingleTicketItem(int fare, TicketType ticketType, boolean isConcessionary, boolean fcAvailable) {
        ItemStack singleTicketItem = new ItemStack(KSDItems.SINGLE_TICKET.get());
        long id = new Random().nextLong();
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        singleTicketTag.putLong("id", id);
        singleTicketTag.putString("ticket_type", ticketType.name());
        singleTicketTag.putInt("fare", fare);
        singleTicketTag.putLong("expire_time", newExpireTime());
        singleTicketTag.putBoolean("is_concessionary", isConcessionary);
        singleTicketTag.putBoolean("fc_available", fcAvailable);
        return singleTicketItem;
    }

    public static void adjustSingleTicketFare(ItemStack singleTicketItem, int addValue) {
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        int originalFare = singleTicketTag.getInt("fare");
        singleTicketTag.putInt("fare", originalFare + addValue);
        singleTicketTag.putLong("expire_time", newExpireTime());
    }
    
    public static String getPrintedData(ItemStack singleTicketItem) {
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.TRADITIONAL_CHINESE);
        int fare = singleTicketTag.getInt("fare");
        String ticketType = singleTicketTag.getString("ticket_type");
        String expire_time = Instant.ofEpochMilli(singleTicketTag.getLong("expire_time")).atZone(ZoneId.of("Asia/Hong_Kong")).format(formatter);
        boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
        boolean fcAvailable = singleTicketTag.getBoolean("fc_available");
        return Text.translatable("gui.ksd.pd_fare", fare).getString() + "\n" +
                Text.translatable("gui.ksd.pd_ticket_type", ticketType).getString() + "\n" +
                Text.translatable("gui.ksd.pd_expire_time", expire_time).getString() + "\n" +
                Text.translatable("gui.ksd.pd_is_concessionary", Text.translatable("gui.ksd." + isConcessionary)).getString() + "\n" +
                Text.translatable("gui.ksd.pd_fc_available", Text.translatable("gui.ksd." + fcAvailable)).getString();
    }

    public static long newExpireTime() {
        return System.currentTimeMillis() + Utilities.EXPIRE_TIME;
    }

    public static int getExpiredFare(long expireTime) {
        double minutes = (System.currentTimeMillis() - expireTime) / 60_000.0F;
        return (int) Math.ceil(2 * (minutes / 60));
    }

    public static ItemStack findSingleTicketItem(long id, Inventory inventory) {
        return Utilities.getInstance().findFilteredItem(inventory, item -> {
            if (!(item.getItem() instanceof ItemSingleTicket)) {
                return false;
            }
            CompoundTag itemTag = item.getOrCreateTag();
            long itemId = itemTag.getLong("id");
            return itemId == id;
        });
    }

    public enum TicketType {
        MTR,
        KCR,
        LRT,
    }
}
