package net.hulan.ivr;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class IVRFabricClientMain implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        IVRClient.init();
    }
}
