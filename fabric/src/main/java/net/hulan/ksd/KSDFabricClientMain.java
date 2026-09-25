package net.hulan.ksd;

import net.fabricmc.api.ClientModInitializer;

public class KSDFabricClientMain implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KSDClientMain.init();
    }
}
