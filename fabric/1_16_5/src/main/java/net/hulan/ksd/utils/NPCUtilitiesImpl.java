package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.hulan.ivr.IVREntities;
import net.hulan.ivr.entity.InteractiveNPC;

public final class NPCUtilitiesImpl {

    private NPCUtilitiesImpl() {
    }

    public static void registerAttributes() {
        FabricDefaultAttributeRegistry.register(IVREntities.TICKETS.get(), InteractiveNPC.createAttributes());
        FabricDefaultAttributeRegistry.register(IVREntities.FARE_ADJUSTMENTS.get(), InteractiveNPC.createAttributes());
    }

    public static boolean isCreative(Player player) {
        return player.abilities.instabuild;
    }

    public static float getYaw(Player player) {
        return player.yRot;
    }
}
