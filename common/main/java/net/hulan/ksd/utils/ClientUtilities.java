package net.hulan.ksd.utils;

import net.minecraft.client.Options;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

public abstract class ClientUtilities {

    private static ClientUtilities instance;

    public static ClientUtilities getInstance() {
        if (instance == null || instance instanceof NullClientUtilities) {
            String version = Utilities.getIVRMinecraftVersion();
            String className = "ClientUtilities_" + version;
            ClientUtilities tempInstance = new NullClientUtilities();
            try {
                Class<?> clazz = Class.forName("net.hulan.ksd.utils." + className);
                tempInstance = (ClientUtilities) clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException |
                     NoSuchMethodException e) {
                e.printStackTrace();
            }
            instance = tempInstance;
        }
        return instance;
    }

    public abstract double getFov(Options clientOptions);

    public abstract Optional<Resource> getResource(ResourceLocation resourceLocation) throws IOException;

    public abstract InputStream openInputStream(Resource resource) throws IOException;

    public static class NullClientUtilities extends ClientUtilities {

        public double getFov(Options clientOptions) {
            return 0;
        }

        public Optional<Resource> getResource(ResourceLocation resourceLocation) {
            return Optional.empty();
        }

        public InputStream openInputStream(Resource resource) {
            return null;
        }
    }
}
