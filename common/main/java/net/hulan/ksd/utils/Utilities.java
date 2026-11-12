package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import mtr.data.EnumHelper;
import mtr.data.RouteType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.msgpack.core.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public abstract class Utilities {

    public static final long EXPIRE_TIME = TimeUnit.MINUTES.toMillis(150);

    public static final RouteType KCR = EnumHelper.valueOf(RouteType.NORMAL, "KCR");

    public static ItemStack findFilteredItem(Inventory inventory, Predicate<ItemStack> filter) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (filter.test(itemStack)) {
                return itemStack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        UtilitiesImpl.registerCommand(command);
    }

    public static void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        UtilitiesImpl.renderGuiItem(poseStack, itemRenderer, font, itemStack, x, y);
    }

    public static void registerItemModelPredicator(Item item, ResourceLocation id, ModelPredictor modelPredictor) {
        UtilitiesImpl.registerItemModelPredicator(item, id, modelPredictor);
    }

    public static BlockBehaviour.Properties createBlockProperties() {
        return UtilitiesImpl.createBlockProperties();
    }

    public static boolean hasModLoaded(String modId) {
        return UtilitiesImpl.hasModLoaded(modId);
    }

    @FunctionalInterface
    public interface ModelPredictor{
        float predictor(ItemStack itemStack, @Nullable ClientLevel world, @Nullable LivingEntity livingEntity);
    }
}
