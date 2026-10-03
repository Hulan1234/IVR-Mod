package net.hulan.ivr.platform;

import net.hulan.ivr.IVREntities;
import net.minecraft.world.entity.player.Player;
import net.hulan.ivr.render.NPCSteveRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@SuppressWarnings("unused")
public class NPCPlatform_1_17_1 extends NPCPlatform {

    protected boolean isCreativePlayer(Player player) {
        return player.getAbilities().instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.getYRot();
    }

    @SubscribeEvent
    public static void registerForgeRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(IVREntities.TICKETS.get(), NPCSteveRenderer::new);
        event.registerEntityRenderer(IVREntities.FARE_ADJUSTMENTS.get(), NPCSteveRenderer::new);
    }
}
