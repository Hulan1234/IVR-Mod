package net.hulan.ivr.platform;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.hulan.ivr.entity.InteractiveNPC;
import net.hulan.ivr.IVREntities;
import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCPlatform_1_19_2 extends NPCPlatform {

    protected boolean isCreativePlayer(Player player) {
        return player.getAbilities().instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.getYRot();
    }

    public void registerAttributes() {
        FabricDefaultAttributeRegistry.register(IVREntities.TICKETS.get(), InteractiveNPC.createAttributes());
        FabricDefaultAttributeRegistry.register(IVREntities.FARE_ADJUSTMENTS.get(), InteractiveNPC.createAttributes());
    }
}
