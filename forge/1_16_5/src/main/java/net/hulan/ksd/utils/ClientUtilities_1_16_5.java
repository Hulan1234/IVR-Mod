package net.hulan.ksd.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
@SuppressWarnings("unused")
public class ClientUtilities_1_16_5 extends ClientUtilities {

    public double getFov(Options clientOptions) {
        return clientOptions.fov;
    }

    public Optional<Resource> getResource(ResourceLocation resourceLocation) throws IOException {
        return Optional.ofNullable(Minecraft.getInstance().getResourceManager().getResource(resourceLocation));
    }

    public InputStream openInputStream(Resource resource) {
        return resource.getInputStream();
    }
}
