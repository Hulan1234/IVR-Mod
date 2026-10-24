package net.hulan.ksd.utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

@Environment(EnvType.CLIENT)
@SuppressWarnings("unused")
public class ClientUtilities_1_19_2 extends ClientUtilities {

    public double getFov(Options clientOptions) {
        return clientOptions.fov().get();
    }

    public Optional<Resource> getResource(ResourceLocation resourceLocation) {
        return Minecraft.getInstance().getResourceManager().getResource(resourceLocation);
    }

    public InputStream openInputStream(Resource resource) throws IOException {
        return resource.open();
    }
}
