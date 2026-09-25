package net.hulan.ivr.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import mtr.mappings.Text;

public class TicketsNPC extends NPC {

    private static final ResourceLocation TEXTURE = new ResourceLocation("ivr", "textures/entity/tickets_npc.png");


    public TicketsNPC(EntityType<? extends TicketsNPC> type, Level level) {
        super(type, level);
        setCustomName(Text.translatable("entity.ivr.tickets_npc"));
        setCustomNameVisible(true);
    }

    public String getRole() {
        return "tickets";
    }

    @Override
    public ResourceLocation getTexture() {
        return TEXTURE;
    }
}
