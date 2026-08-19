package net.hulan.ivr.block;

import mtr.block.BlockAPGGlassEnd;
import net.hulan.ivr.IVRItems;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;

public class BlockKCRAPGGlassEnd extends BlockAPGGlassEnd {

    public BlockKCRAPGGlassEnd() {
    }

    public @NotNull Item asItem() {
        return IVRItems.KCR_APG_GLASS_END.get();
    }
}
