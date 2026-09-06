package net.hulan.ksd.block;

import mtr.block.IBlock;
import mtr.mappings.BlockDirectionalMapper;
import mtr.mappings.Utilities;
import net.hulan.ksd.data.FirstClassValidationSystem;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.item.ItemOctopus;
import net.hulan.ksd.item.ItemSingleTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BlockKCRFirstClassProcessor extends BlockDirectionalMapper {

    public static final IntegerProperty TYPE = IntegerProperty.create("type", 0, 3);

    public BlockKCRFirstClassProcessor() {
        super(Properties.of(Material.METAL, MaterialColor.GOLD).requiresCorrectToolForDrops().strength(1).lightLevel((state) -> 6));
        registerDefaultState(defaultBlockState().setValue(TYPE, 0));
    }

    @SuppressWarnings("deprecation")
    public @NotNull InteractionResult use(BlockState blockState, Level world, BlockPos blockPos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (!world.isClientSide && railwayData != null) {
            ItemStack holdingItem = player.getItemInHand(interactionHand);
            FirstClassValidationSystem.FirstClassState firstClassState = FirstClassValidationSystem.FirstClassState.MTR;
            if (holdingItem.getItem() instanceof ItemSingleTicket || holdingItem.getItem() instanceof ItemOctopus) {
                firstClassState = FirstClassValidationSystem.validate(world, railwayData, player, holdingItem, holdingItem.getItem() instanceof ItemOctopus);
            }
            switch (firstClassState) {
                case VALIDATED -> world.setBlockAndUpdate(blockPos, blockState.setValue(TYPE, 1));
                case VALIDATED_CONCESSIONARY -> world.setBlockAndUpdate(blockPos, blockState.setValue(TYPE, 2));
                default -> world.setBlockAndUpdate(blockPos, blockState.setValue(TYPE, 3));
            }
            Utilities.scheduleBlockTick(world, blockPos, this, 20);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void tick(BlockState state, ServerLevel world, BlockPos pos) {
        world.setBlockAndUpdate(pos, state.setValue(TYPE, 0));
    }

    @SuppressWarnings("deprecation")
    public @NotNull VoxelShape getShape(BlockState state, BlockGetter blockGetter, BlockPos pos, CollisionContext collisionContext) {
        Direction direction = IBlock.getStatePropertySafe(state, FACING);
        return IBlock.getVoxelShapeByDirection(5, 4, 0,11, 14, 1, direction);
    }

    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TYPE);
    }
}
