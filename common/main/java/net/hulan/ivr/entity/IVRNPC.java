package net.hulan.ivr.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** Compatibility base used by version-specific renderers. */
public abstract class IVRNPC extends PathfinderMob {

    protected IVRNPC(EntityType<? extends IVRNPC> type, Level level) {
        super(type, level);
        setNoAi(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    public net.minecraft.resources.ResourceLocation getTexture() {
        return null;
    }
}
