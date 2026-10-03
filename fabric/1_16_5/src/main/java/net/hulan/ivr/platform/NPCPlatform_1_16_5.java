package net.hulan.ivr.platform;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.hulan.ivr.entity.InteractiveNPC;
import net.hulan.ivr.IVREntities;
import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCPlatform_1_16_5 extends NPCPlatform {

    protected boolean isCreativePlayer(Player player) {
        return player.abilities.instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.yRot;
    }

    public void registerAttributes() {
        FabricDefaultAttributeRegistry.register(IVREntities.TICKETS.get(), InteractiveNPC.createAttributes());
        FabricDefaultAttributeRegistry.register(IVREntities.FARE_ADJUSTMENTS.get(), InteractiveNPC.createAttributes());
    }
}
