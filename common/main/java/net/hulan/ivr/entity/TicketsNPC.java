package net.hulan.ivr.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import mtr.mappings.Text;

public class TicketsNPC extends NPC {

    public TicketsNPC(EntityType<? extends TicketsNPC> type, Level level) {
        super(type, level);
        setCustomName(Text.translatable("entity.ivr.tickets_npc"));
        setCustomNameVisible(true);
    }

    public String getRole() {
        return "tickets";
    }
}
