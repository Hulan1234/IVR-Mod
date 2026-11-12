package net.hulan.ksd.utils;

import net.minecraft.client.Options;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public class ClientUtilities {

    public static double getFov(Options clientOptions) {
        return ClientUtilitiesImpl.getFov(clientOptions);
    }

    public static int getRenderDistance(Options clientOptions) {
        return ClientUtilitiesImpl.getRenderDistance(clientOptions);
    }

    public static Optional<Resource> getResource(ResourceLocation resourceLocation) throws IOException {
        return ClientUtilitiesImpl.getResource(resourceLocation);
    }

    public static InputStream openInputStream(Resource resource) throws IOException {
        return ClientUtilitiesImpl.openInputStream(resource);
    }
}
