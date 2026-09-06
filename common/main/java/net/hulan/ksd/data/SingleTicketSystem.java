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

public class SingleTicketSystem {

    public static ItemStack createSingleTicketItem(int fare, TicketType ticketType, boolean isConcessionary, boolean fcAvailable) {
        ItemStack stItem = new ItemStack(KSDItems.SINGLE_TICKET.get());
        long id = new Random().nextLong();
        CompoundTag stTag = stItem.getOrCreateTag();
        stTag.putLong("id", id);
        stTag.putString("ticket_type", ticketType.name());
        stTag.putInt("fare", fare);
        stTag.putLong("expire_time", KCRTicketSystem.newExpireTime());
        stTag.putBoolean("is_concessionary", isConcessionary);
        stTag.putBoolean("fc_available", fcAvailable);
        return stItem;
    }

    public static void adjustSingleTicketFare(ItemStack stItem, int addValue) {
        CompoundTag stTag = stItem.getOrCreateTag();
        int originalFare = stTag.getInt("fare");
        stTag.putInt("fare", originalFare + addValue);
        stTag.putLong("expire_time", KCRTicketSystem.newExpireTime());
    }
    
    public static String getPrintedData(ItemStack stItem) {
        CompoundTag stTag = stItem.getOrCreateTag();
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.TRADITIONAL_CHINESE);
        int fare = stTag.getInt("fare");
        String ticketType = stTag.getString("ticket_type");
        String expire_time = Instant.ofEpochMilli(stTag.getLong("expire_time")).atZone(ZoneId.of("Asia/Hong_Kong")).format(formatter);
        boolean isConcessionary = stTag.getBoolean("is_concessionary");
        boolean fcAvailable = stTag.getBoolean("fc_available");
        return Text.translatable("gui.ksd.pd_fare", fare).getString() + "\n" +
                Text.translatable("gui.ksd.pd_ticket_type", ticketType).getString() + "\n" +
                Text.translatable("gui.ksd.pd_expire_time", expire_time).getString() + "\n" +
                Text.translatable("gui.ksd.pd_is_concessionary", Text.translatable("gui.ksd." + isConcessionary)).getString() + "\n" +
                Text.translatable("gui.ksd.pd_fc_available", Text.translatable("gui.ksd." + fcAvailable)).getString();
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
