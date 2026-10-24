package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCUtilities_1_17_1 extends NPCUtilities {

    protected boolean isCreativePlayer(Player player) {
        return player.getAbilities().instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.getYRot();
    }
}
