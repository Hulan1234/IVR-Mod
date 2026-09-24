package net.hulan.ivr.platform;

import mtr.RegistryObject;
import net.hulan.ivr.entity.NPC;
import net.minecraft.SharedConstants;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.InvocationTargetException;

/** Version-specific bridge for NPC registration, attributes, and rendering. */
public abstract class NPCPlatform {

    private static NPCPlatform instance;

    public static NPCPlatform getInstance() {
        if (instance != null) {
            return instance;
        }

        String version = minecraftVersion();
        String className = "net.hulan.ivr.platform.NPCPlatform_" + version;
        try {
            instance = (NPCPlatform) Class.forName(className).getDeclaredConstructor().newInstance();
            return instance;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No NPC platform implementation for " + version, exception);
        }
    }

    private static String minecraftVersion() {
        String version = SharedConstants.getCurrentVersion().getName();
        if (version.startsWith("1.16")) {
            return "1_16_5";
        }
        if (version.startsWith("1.17")) {
            return "1_17_1";
        }
        if (version.equals("1.19") || version.equals("1.19.1") || version.equals("1.19.2")) {
            return "1_19_2";
        }
        if (version.equals("1.19.3") || version.equals("1.19.4")) {
            return "1_19_4";
        }
        return "1_18_2";
    }

    @FunctionalInterface
    public interface EntityRegistrar {
        void register(String id, RegistryObject<? extends EntityType<? extends NPC>> entity);
    }

    public abstract void registerEntities(EntityRegistrar registrar);

    public abstract void registerClientRenderers();

    public void registerAttributes() {}

    public static boolean isCreative(Player player) {
        try {
            Object abilities = player.getClass().getMethod("getAbilities").invoke(player);
            return abilities.getClass().getField("instabuild").getBoolean(abilities);
        } catch (ReflectiveOperationException ignored) {
            try {
                Object abilities = player.getClass().getField("abilities").get(player);
                return abilities.getClass().getField("instabuild").getBoolean(abilities);
            } catch (ReflectiveOperationException ignoredLegacy) {
                return false;
            }
        }
    }

    public static float getYaw(Player player) {
        try {
            return ((Number) player.getClass().getMethod("getYRot").invoke(player)).floatValue();
        } catch (ReflectiveOperationException ignored) {
            try {
                return player.getClass().getField("rotationYaw").getFloat(player);
            } catch (ReflectiveOperationException ignoredLegacy) {
                return 0;
            }
        }
    }
}
