package net.hulan.ksd.utils;

import net.minecraft.client.Options;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class ClientUtilitiesImpl {

    private ClientUtilitiesImpl() {
    }

    public static double getFov(Options clientOptions) {
        return clientOptions.fov;
    }

    public static int getRenderDistance(Options clientOptions) {
        return clientOptions.renderDistance;
    }

    public static Optional<Resource> getResource(ResourceLocation resourceLocation) throws IOException {
        return Optional.ofNullable(Minecraft.getInstance().getResourceManager().getResource(resourceLocation));
    }

    public static InputStream openInputStream(Resource resource) {
        return resource.getInputStream();
    }
}
