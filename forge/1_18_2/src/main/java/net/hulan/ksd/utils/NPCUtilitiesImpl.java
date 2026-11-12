package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;

public final class NPCUtilitiesImpl {

    private NPCUtilitiesImpl() {
    }

    public static void registerAttributes() {
    }

    public static boolean isCreative(Player player) {
        return player.getAbilities().instabuild;
    }

    public static float getYaw(Player player) {
        return player.getYRot();
    }
}
