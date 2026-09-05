package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import mtr.data.EnumHelper;
import mtr.data.RouteType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public abstract class Utilities {

    public static final long EXPIRE_TIME = TimeUnit.MINUTES.toMillis(150);
    public static final RouteType KCR = EnumHelper.valueOf(RouteType.NORMAL, "KCR");
    private static Utilities instance;
    
    public static Utilities getInstance() {
        if (instance == null || instance instanceof NullUtilities) {
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

    public ItemStack findFilteredItem(Inventory inventory, Predicate<ItemStack> filter) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (filter.test(itemStack)) {
                return itemStack;
            }
        }
        return ItemStack.EMPTY;
    }

    public abstract void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command);

    public abstract void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y);

    private static class NullUtilities extends Utilities {

        public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        }

        public void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        }
    }
}
