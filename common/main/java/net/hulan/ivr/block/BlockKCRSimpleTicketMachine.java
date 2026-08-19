package net.hulan.ivr.block;

import mtr.block.BlockDirectionalDoubleBlockBase;
import mtr.block.IBlock;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BlockKCRSimpleTicketMachine extends BlockDirectionalDoubleBlockBase {

    public BlockKCRSimpleTicketMachine() {
        super(BlockBehaviour.Properties.of(Material.METAL, MaterialColor.COLOR_GRAY)
                .requiresCorrectToolForDrops()
                .strength(2)
                .lightLevel(state -> 5)
                .noOcclusion());
    }

    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState blockState, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        if (!world.isClientSide) {
            KSDPacketServer.openKCRTicketMachineScreenS2C((ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }

    public BlockState updateShape(BlockState state, Direction direction, BlockState newState, LevelAccessor world, BlockPos pos, BlockPos posFrom) {
        if (IBlock.getSideDirection(state) == direction && !newState.is(this)) {
            return Blocks.AIR.defaultBlockState();
        } else {
            return super.updateShape(state, direction, newState, world, pos, posFrom);
        }
    }

    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection();
        return IBlock.isReplaceable(ctx, Direction.UP, 2)
                && IBlock.isReplaceable(ctx, facing.getClockWise(), 2) ? defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER) : null;
    }

    public void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        BlockPos offsetPos = pos;
        if (IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER) {
            offsetPos = pos.below();
        }
        if (IBlock.getStatePropertySafe(state, SIDE) == EnumSide.RIGHT) {
            offsetPos = offsetPos.relative(IBlock.getSideDirection(state));
        }
        IBlock.onBreakCreative(world, player, offsetPos);
        super.playerWillDestroy(world, pos, state, player);
    }

    public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity livingEntity, ItemStack itemStack) {
        if (!world.isClientSide) {
            Direction facing = IBlock.getStatePropertySafe(state, FACING);
            Direction side = facing.getClockWise();
            BlockPos abovePos = pos.above();
            BlockPos sidePos = pos.relative(side);
            BlockPos sideAbovePos = sidePos.above();
            world.setBlock(abovePos, defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.UPPER).setValue(SIDE, EnumSide.LEFT), 3);
            world.setBlock(sidePos, defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER).setValue(SIDE, EnumSide.RIGHT), 3);
            world.setBlock(sideAbovePos, defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.UPPER).setValue(SIDE, EnumSide.RIGHT), 3);
            world.updateNeighborsAt(pos, Blocks.AIR);
            state.updateNeighbourShapes(world, pos, 3);
        }
    }

    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext) {
        return Shapes.block();
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SIDE);
    }
}
