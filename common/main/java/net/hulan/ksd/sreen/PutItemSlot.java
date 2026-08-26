package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.IGui;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.item.ItemStack;

/** Client-side wrapper for one temporary inventory slot that is not synchronized to the real inventory. */
public class PutItemSlot implements WidgetMapper, IGui {

    public static final int SIZE = 18;
    private final int inventoryIndex;
    private ItemStack itemStack;
    private int x;
    private int y;

    public PutItemSlot(int inventoryIndex, ItemStack itemStack) {
        this.inventoryIndex = inventoryIndex;
        this.itemStack = itemStack.copy();
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Uses a fixed border and highlights empty and occupied slots equally on hover. */
    public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
        Minecraft minecraft = Minecraft.getInstance();
        Gui.fill(poseStack, x, y, x + SIZE, y + SIZE, 0xFF8B8B8B);
        Gui.fill(poseStack, x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0xFF373737);
        ItemStack itemStack = getItemStack();
        if (!itemStack.isEmpty()) {
            Utilities.getInstance().renderGuiItem(poseStack, minecraft.getItemRenderer(), minecraft.font, itemStack, x + 1, y + 1);
        }
        if (isMouseOver(mouseX, mouseY)) {
            Gui.fill(poseStack, x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0x60FFFFFF);
        }
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseY >= y && mouseX < x + SIZE && mouseY < y + SIZE;
    }

    public int getInventoryIndex() {
        return inventoryIndex;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public ItemStack takeStack() {
        ItemStack stack = itemStack;
        itemStack = ItemStack.EMPTY;
        return stack;
    }

    public void setItemStack(ItemStack itemStack) {
        this.itemStack = itemStack;
    }
}
