package net.hulan.ivr.platform;

import mtr.RegistryClient;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.hulan.ivr.entity.NPC;
import net.hulan.ivr.entity.NPCs;
import net.hulan.ivr.render.NPCSteveRenderer;

public class NPCPlatform_1_19_2 extends NPCPlatform {
    @Override
    public void registerEntities(EntityRegistrar registrar) {
        registrar.register("tickets", NPCs.TICKETS);
        registrar.register("fare_adjustments", NPCs.FARE_ADJUSTMENTS);
    }

    @Override
    public void registerClientRenderers() {
        RegistryClient.registerEntityRenderer(NPCs.TICKETS.get(), NPCSteveRenderer::new);
        RegistryClient.registerEntityRenderer(NPCs.FARE_ADJUSTMENTS.get(), NPCSteveRenderer::new);
    }

    @Override
    public void registerAttributes() {
        FabricDefaultAttributeRegistry.register(NPCs.TICKETS.get(), NPC.createAttributes());
        FabricDefaultAttributeRegistry.register(NPCs.FARE_ADJUSTMENTS.get(), NPC.createAttributes());
    }
}
