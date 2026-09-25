package net.hulan.ivr.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import mtr.mappings.Text;

public class FareAdjustmentsNPC extends NPC {

    private static final ResourceLocation TEXTURE = new ResourceLocation("ivr", "textures/entity/fare_adjustments_npc.png");

    public FareAdjustmentsNPC(EntityType<? extends FareAdjustmentsNPC> type, Level level) {
        super(type, level);
        setCustomName(Text.translatable("entity.ivr.fare_adjustments_npc"));
        setCustomNameVisible(true);
    }

    @Override
    public String getRole() {
        return "fare_adjustments";
    }

    @Override
    public ResourceLocation getTexture() {
        return TEXTURE;
    }
}
