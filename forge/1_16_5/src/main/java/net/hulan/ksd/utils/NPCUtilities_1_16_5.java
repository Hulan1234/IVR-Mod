package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;

@SuppressWarnings("unused")
public class NPCUtilities_1_16_5 extends NPCUtilities {

    protected boolean isCreativePlayer(Player player) {
        return player.abilities.instabuild;
    }

    protected float getPlayerYaw(Player player) {
        return player.yRot;
    }
}
