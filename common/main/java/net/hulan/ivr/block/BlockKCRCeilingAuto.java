package net.hulan.ivr.block;

import mtr.block.BlockCeilingAuto;
import net.hulan.ksd.utils.Utilities;

public class BlockKCRCeilingAuto extends BlockCeilingAuto {

    public BlockKCRCeilingAuto() {
        super(Utilities.getInstance().createBlockProperties().requiresCorrectToolForDrops().strength(2.0F).lightLevel((state) -> 15));
    }
}
