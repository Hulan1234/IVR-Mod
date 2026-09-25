package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;

@SuppressWarnings("unused")
public class Utilities_1_19_4 extends Utilities {

    public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        CommandRegistrationCallback.EVENT.register((dispatcher, ct, selection) ->
                dispatcher.register(command));
    }

    public void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        itemRenderer.renderAndDecorateItem(poseStack, itemStack, x, y);
        itemRenderer.renderGuiItemDecorations(poseStack, font, itemStack, x, y);
    }

    @SuppressWarnings("deprecation")
    public void registerTicketItemModelPredicator(Item item, ResourceLocation id, ModelPredictor modelPredictor) {
        FabricModelPredicateProviderRegistry.register(item, id, (itemStack, clientLevel, livingEntity, i)
                -> modelPredictor.predictor(itemStack, clientLevel, livingEntity));
    }

    public BlockBehaviour.Properties createBlockProperties() {
        return BlockBehaviour.Properties.of(Material.METAL);
    }
}
