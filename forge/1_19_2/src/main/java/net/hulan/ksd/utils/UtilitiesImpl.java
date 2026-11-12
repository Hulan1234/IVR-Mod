package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.platform.Platform;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class UtilitiesImpl {

    private UtilitiesImpl() {
    }

    public static void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(command));
    }

    public static void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        itemRenderer.renderAndDecorateItem(itemStack, x, y);
        itemRenderer.renderGuiItemDecorations(font, itemStack, x, y);
    }

    public static void registerItemModelPredicator(Item item, ResourceLocation id, Utilities.ModelPredictor modelPredictor) {
        ItemProperties.register(item, id, (itemStack, clientLevel, livingEntity, seed) ->
                modelPredictor.predictor(itemStack, clientLevel, livingEntity));
    }

    public static BlockBehaviour.Properties createBlockProperties() {
        return BlockBehaviour.Properties.of(Material.METAL, MaterialColor.COLOR_GRAY);
    }

    public static boolean hasModLoaded(String modId) {
        return Platform.isModLoaded(modId);
    }
}
