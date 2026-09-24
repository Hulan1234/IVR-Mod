package net.hulan.ivr.entity;

import mtr.RegistryObject;
import net.minecraft.world.entity.EntityType;

/** Compatibility aliases for platform implementations. */
public interface IVRNPCs {
    RegistryObject<EntityType<TicketsNPC>> TICKETS = NPCs.TICKETS;
    RegistryObject<EntityType<FareAdjustmentsNPC>> FARE_ADJUSTMENTS = NPCs.FARE_ADJUSTMENTS;
}
