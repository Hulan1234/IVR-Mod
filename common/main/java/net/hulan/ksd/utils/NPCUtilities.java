package net.hulan.ksd.utils;

import net.minecraft.world.entity.player.Player;

public abstract class NPCUtilities {

    private static NPCUtilities instance;

    public static NPCUtilities getInstance() {
        if (instance != null) {
            return instance;
        }
        String version = Utilities.getIVRMinecraftVersion();
        String className = "net.hulan.ksd.utils.NPCUtilities_" + version;
        try {
            instance = (NPCUtilities) Class.forName(className).getDeclaredConstructor().newInstance();
            return instance;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not load NPC Utilities in " + version, exception);
        }
    }

    public void registerAttributes() {
    }

    public static boolean isCreative(Player player) {
        return getInstance().isCreativePlayer(player);
    }

    public static float getYaw(Player player) {
        return getInstance().getPlayerYaw(player);
    }

    protected abstract boolean isCreativePlayer(Player player);

    protected abstract float getPlayerYaw(Player player);
}
