package net.hulan.ksd.utils;

import mtr.data.EnumHelper;
import mtr.data.RouteType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

public abstract class Utilities {

    public static final long TWO_HOURS = TimeUnit.HOURS.toMillis(2);
    public static final long DAY = TimeUnit.DAYS.toMillis(1);
    public static final RouteType KCR_CLASSICAL = EnumHelper.valueOf(RouteType.NORMAL, "KCR_CLASSICAL");
    public static final RouteType KCR_MODERN = EnumHelper.valueOf(RouteType.NORMAL, "KCR_MODERN");
    private static Utilities instance;
    
    public static Utilities getInstance() {
        if (instance == null) {
            String version = getIVRMinecraftVersion();
            String className = "Utilities_" + version;
            Utilities tempInstance = new NullUtilities();
            try {
                Class<?> clazz = Class.forName("net.hulan.ksd.utils." + className);
                tempInstance = (Utilities) clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException |
                     NoSuchMethodException e) {
                e.printStackTrace();
            }
            instance = tempInstance;
        }
        return instance;
    }

    public static String getMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(ModContainer::getMetadata)
                .map(ModMetadata::getVersion)
                .map(Version::getFriendlyString)
                .orElse("1.18.2");
    }

    public static String getIVRMinecraftVersion() {
        String originVersion = getMinecraftVersion();
        if (originVersion.contains("1.16")) {
            return "1_16_5";
        } else if (originVersion.contains("1.17")) {
            return "1_17_1";
        } else if (originVersion.equals("1.19.2")
                || originVersion.equals("1.19.1")
                || originVersion.equals("1.19")) {
            return "1_19_2";
        } else if (originVersion.equals("1.19.3")
                || originVersion.equals("1.19.4")) {
            return "1_19_4";
        }
        return "1_18_2";
    }

    public abstract void registerCommand();

    private static class NullUtilities extends Utilities {

        public void registerCommand() {
        }
    }
}
