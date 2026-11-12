package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.fabricmc.loader.api.FabricLoader;

public final class UtilitiesImpl {

    private UtilitiesImpl() {
    }

    public static void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        CommandRegistrationCallback.EVENT.register((dispatcher, ct, selection) ->
                dispatcher.register(command));
    }

    public static void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        itemRenderer.renderAndDecorateItem(itemStack, x, y);
        itemRenderer.renderGuiItemDecorations(font, itemStack, x, y);
    }

    public static void registerItemModelPredicator(Item item, ResourceLocation id, Utilities.ModelPredictor modelPredictor) {
        FabricModelPredicateProviderRegistry.register(item, id, (itemStack, clientLevel, livingEntity, i)
                -> modelPredictor.predictor(itemStack, clientLevel, livingEntity));
    }

    public static BlockBehaviour.Properties createBlockProperties() {
        return BlockBehaviour.Properties.of(Material.METAL);
    }

    public static boolean hasModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
}
