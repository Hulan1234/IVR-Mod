package net.hulan.ivr.entity;

import mtr.RegistryObject;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public interface NPCs {
    RegistryObject<EntityType<TicketsNPC>> TICKETS = new RegistryObject<>(() -> EntityType.Builder.of(TicketsNPC::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("tickets"));
    RegistryObject<EntityType<FareAdjustmentsNPC>> FARE_ADJUSTMENTS = new RegistryObject<>(() -> EntityType.Builder.of(FareAdjustmentsNPC::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("fare_adjustments"));
}
