package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

@SuppressWarnings("unused")
public class Utilities_1_18_2 extends Utilities {

    public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                event.getDispatcher().register(command));
    }

    public void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        itemRenderer.renderAndDecorateItem(itemStack, x, y);
        itemRenderer.renderGuiItemDecorations(font, itemStack, x, y);
    }

    public void registerItemModelPredicator(Item item, ResourceLocation id, ModelPredictor modelPredictor) {
        ItemProperties.register(
                item,
                id,
                (itemStack, clientLevel, livingEntity, seed) -> modelPredictor.predictor(itemStack, clientLevel, livingEntity)
        );
    }

    public BlockBehaviour.Properties createBlockProperties() {
        return BlockBehaviour.Properties.of(Material.METAL);
    }
}
