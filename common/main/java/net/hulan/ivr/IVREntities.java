package net.hulan.ivr;

import mtr.RegistryObject;
import net.hulan.ivr.entity.FareAdjustmentsNPC;
import net.hulan.ivr.entity.TicketsNPC;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public interface IVREntities {

    RegistryObject<EntityType<TicketsNPC>> TICKETS = new RegistryObject<>(() -> EntityType.Builder.of(TicketsNPC::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("tickets"));
    RegistryObject<EntityType<FareAdjustmentsNPC>> FARE_ADJUSTMENTS = new RegistryObject<>(() -> EntityType.Builder.of(FareAdjustmentsNPC::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("fare_adjustments"));
}
