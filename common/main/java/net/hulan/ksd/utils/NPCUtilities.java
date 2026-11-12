package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;

public class NPCUtilities {

    public static void registerAttributes() {
        NPCUtilitiesImpl.registerAttributes();
    }

    public static boolean isCreative(Player player) {
        return NPCUtilitiesImpl.isCreative(player);
    }

    public static float getYaw(Player player) {
        return NPCUtilitiesImpl.getYaw(player);
    }
}
