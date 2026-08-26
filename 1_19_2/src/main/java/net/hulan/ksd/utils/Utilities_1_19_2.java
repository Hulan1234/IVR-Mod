package net.hulan.ksd.utils;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;

public class Utilities_1_19_2 extends Utilities {

    public void renderGuiItem(PoseStack poseStack, ItemRenderer itemRenderer, Font font, ItemStack itemStack, int x, int y) {
        itemRenderer.renderAndDecorateItem(itemStack, x, y);
        itemRenderer.renderGuiItemDecorations(font, itemStack, x, y);
    }

    public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        CommandRegistrationCallback.EVENT.register((dispatcher, ct, selection) -> {
            dispatcher.register(command);
        });
    }
}
