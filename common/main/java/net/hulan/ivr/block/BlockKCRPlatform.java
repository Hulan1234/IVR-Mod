package net.hulan.ivr.block;

import mtr.mappings.BlockDirectionalMapper;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;

public class BlockKCRPlatform extends BlockDirectionalMapper {

    public BlockKCRPlatform() {
        super(Utilities.getInstance().createBlockProperties().requiresCorrectToolForDrops().strength(2.0F));
    }

    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
