package net.hulan.ksd;

import mtr.data.EnumHelper;
import net.fabricmc.api.ClientModInitializer;
import net.hulan.ksd.data.SingleTicketSystem;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.nbt.CompoundTag;

public class KSDFabricClientMain implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KSDClientMain.init();
        Utilities.getInstance().registerTicketItemModelPredicator((ticketItem, world, entity) -> {
            CompoundTag ticketTag = ticketItem.getOrCreateTag();
            SingleTicketSystem.TicketType ticketType = EnumHelper.valueOf(SingleTicketSystem.TicketType.MTR, ticketTag.getString("ticket_type"));
            boolean isConcessionary = ticketTag.getBoolean("is_concessionary");
            boolean fcAvailable = ticketTag.getBoolean("fc_available");
            return ticketType.getModelPredicateValue(isConcessionary, fcAvailable);
        });
    }
}
