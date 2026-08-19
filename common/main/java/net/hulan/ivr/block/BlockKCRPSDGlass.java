package net.hulan.ivr.block;

import mtr.block.BlockPSDGlass;
import net.hulan.ivr.IVRItems;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;

public class BlockKCRPSDGlass extends BlockPSDGlass {

    private final int style;

    public BlockKCRPSDGlass(int style) {
        super(style);
        this.style = style;
    }

    @Override
    public @NotNull Item asItem() {
        return style == 0 ? IVRItems.KCR_PSD_GLASS_1.get() : IVRItems.KCR_PSD_GLASS_2.get();
    }
}
