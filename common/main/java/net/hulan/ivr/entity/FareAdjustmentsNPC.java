package net.hulan.ivr.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import mtr.mappings.Text;

public class FareAdjustmentsNPC extends NPC {
    public FareAdjustmentsNPC(EntityType<? extends FareAdjustmentsNPC> type, Level level) {
        super(type, level);
        setCustomName(Text.translatable("entity.ivr.fare_adjustments_npc"));
        setCustomNameVisible(true);
    }

    @Override
    public String getRole() {
        return "fare_adjustments";
    }
}
