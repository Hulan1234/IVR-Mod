package net.hulan.ivr.platform;

import mtr.RegistryClient;
import net.hulan.ivr.entity.NPCs;
import net.hulan.ivr.render.NPCSteveRenderer;
import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCPlatform_1_18_2 extends NPCPlatform {

    protected boolean isCreativePlayer(Player player) {
        return player.getAbilities().instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.getYRot();
    }

    public void registerEntities(EntityRegistrar registrar) {
        registrar.register("tickets", NPCs.TICKETS);
        registrar.register("fare_adjustments", NPCs.FARE_ADJUSTMENTS);
    }

    public void registerClientRenderers() {
        RegistryClient.registerEntityRenderer(NPCs.TICKETS.get(), NPCSteveRenderer::new);
        RegistryClient.registerEntityRenderer(NPCs.FARE_ADJUSTMENTS.get(), NPCSteveRenderer::new);
    }
}
