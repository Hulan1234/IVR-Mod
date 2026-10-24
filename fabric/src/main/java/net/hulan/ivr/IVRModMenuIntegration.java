package net.hulan.ivr;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.hulan.ivr.screen.IVRConfigScreen;
import net.minecraft.client.Minecraft;

public final class IVRModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new IVRConfigScreen(() -> Minecraft.getInstance().setScreen(parent));
    }
}
