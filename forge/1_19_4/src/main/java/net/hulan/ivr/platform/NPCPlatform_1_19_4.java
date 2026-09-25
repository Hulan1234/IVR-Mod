package net.hulan.ivr.platform;

import mtr.RegistryClient;
import net.hulan.ivr.entity.IVRNPCs;
import net.hulan.ivr.render.NPCSteveRenderer;
import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCPlatform_1_19_4 extends NPCPlatform {
    @Override protected boolean isCreativePlayer(Player player) { return player.getAbilities().instabuild; }
    @Override protected float getPlayerYaw(Player player) { return player.getYRot(); }
    @Override
    public void registerEntities(EntityRegistrar registrar) {
        registrar.register("tickets", IVRNPCs.TICKETS);
        registrar.register("fare_adjustments", IVRNPCs.FARE_ADJUSTMENTS);
    }

    @Override
    public void registerClientRenderers() {
        RegistryClient.registerEntityRenderer(IVRNPCs.TICKETS.get(), NPCSteveRenderer::new);
        RegistryClient.registerEntityRenderer(IVRNPCs.FARE_ADJUSTMENTS.get(), NPCSteveRenderer::new);
    }
}
