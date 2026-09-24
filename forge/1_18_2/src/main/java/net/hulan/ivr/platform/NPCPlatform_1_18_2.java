package net.hulan.ivr.platform;

import mtr.RegistryClient;
import net.hulan.ivr.entity.IVRNPCs;
import net.hulan.ivr.render.NPCSteveRenderer;

public class NPCPlatform_1_18_2 extends NPCPlatform {
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
