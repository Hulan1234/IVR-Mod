package net.hulan.ivr.block;

import mtr.block.IBlock;
import mtr.data.TicketSystem;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockDirectionalMapper;
import mtr.mappings.EntityBlockMapper;
import net.hulan.ivr.IVRBlockEntityTypes;
import net.hulan.ksd.data.KCRTicketSystem;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import static mtr.block.BlockTicketBarrier.OPEN;

public class BlockKCRTicketBarrier extends BlockDirectionalMapper implements EntityBlockMapper {

    public final boolean isEntry;

    public BlockKCRTicketBarrier(boolean isEntry) {
        super(net.hulan.ksd.utils.Utilities.createBlockProperties().requiresCorrectToolForDrops().strength(2.0F).noOcclusion());
        this.isEntry = isEntry;
    }

    @Override
    public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
        return new TileEntityKCRTicketBarrier(pos, state, isEntry);
    }

    @SuppressWarnings("deprecation")
    public @NotNull InteractionResult use(BlockState state,
                                          Level world,
                                          BlockPos pos,
                                          Player player,
                                          InteractionHand interactionHand,
                                          BlockHitResult blockHitResult) {
        if (!world.isClientSide) {
            ItemStack itemInHand = player.getItemInHand(interactionHand);
            if (itemInHand.getItem() instanceof ItemSingleTicket || itemInHand.getItem() instanceof ItemOctopus) {
                boolean wasOpen = state.getValue(OPEN).isOpen();
                TicketSystem.EnumTicketBarrierOpen canOpen = KCRTicketSystem.check(
                        world,
                        player,
                        pos,
                        itemInHand,
                        isEntry,
                        !isEntry,
                        itemInHand.getItem() instanceof ItemOctopus);
                world.setBlockAndUpdate(pos, state.setValue(OPEN, canOpen));
                if (!wasOpen && canOpen.isOpen()) {
                    if (world.getBlockEntity(pos) instanceof TileEntityKCRTicketBarrier blockEntity) {
                        blockEntity.startOpenAnimation();
                    }
                }
                if (canOpen.isOpen() && !world.getBlockTicks().hasScheduledTick(pos, this)) {
                    mtr.mappings.Utilities.scheduleBlockTick(world, pos, this, 40);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    public void tick(BlockState state, ServerLevel world, BlockPos pos) {
        world.setBlockAndUpdate(pos, state.setValue(OPEN, TicketSystem.EnumTicketBarrierOpen.CLOSED));
    }

    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()).setValue(OPEN, TicketSystem.EnumTicketBarrierOpen.CLOSED);
    }

    @SuppressWarnings("deprecation")
    public @NotNull VoxelShape getShape(BlockState state, BlockGetter blockGetter, BlockPos pos, CollisionContext collisionContext) {
        Direction facing = IBlock.getStatePropertySafe(state, FACING);
        return IBlock.getVoxelShapeByDirection(12.0F, 0.0F, 0.0F, 16.0F, 15.0F, 16.0F, facing);
    }

    @SuppressWarnings("deprecation")
    public @NotNull VoxelShape getCollisionShape(BlockState state, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext) {
        Direction facing = IBlock.getStatePropertySafe(state, FACING);
        TicketSystem.EnumTicketBarrierOpen open = IBlock.getStatePropertySafe(state, OPEN);
        VoxelShape base = IBlock.getVoxelShapeByDirection(15.0F, 0.0F, 0.0F, 16.0F, 24.0F, 16.0F, facing);
        return open.isOpen() ? base : Shapes.or(IBlock.getVoxelShapeByDirection(0.0F, 0.0F, 7.0F, 16.0F, 24.0F, 9.0F, facing), base);
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    public static class TileEntityKCRTicketBarrier extends BlockEntityClientSerializableMapper {

        public boolean isEntry;
        private long animationStartTick = Long.MIN_VALUE;
        private static final String ANIMATION_START_TICK_KEY = "animation_start_tick";

        public TileEntityKCRTicketBarrier(BlockPos pos, BlockState state, boolean isEntry) {
            super(isEntry ? IVRBlockEntityTypes.KCR_TICKET_BARRIER_ENTRY_TILE_ENTITY.get() : IVRBlockEntityTypes.KCR_TICKET_BARRIER_EXIT_TILE_ENTITY.get(), pos, state);
            this.isEntry = isEntry;
        }

        public void startOpenAnimation() {
            if (level != null && !level.isClientSide) {
                animationStartTick = level.getGameTime();
                setChanged();
                syncData();
            }
        }

        public long getAnimationStartTick() {
            return animationStartTick;
        }

        public float getAnimationAngle(float tickDelta) {
            if (level == null || animationStartTick == Long.MIN_VALUE) {
                return 0.0F;
            }

            double progress = Math.max(0.0D, Math.min(1.0D, (level.getGameTime() + tickDelta - animationStartTick) / 10.0D));
            double easedProgress = progress * progress * (3.0D - 2.0D * progress);
            return (float)(easedProgress * 120.0D);
        }

        @Override
        public void readCompoundTag(net.minecraft.nbt.CompoundTag compoundTag) {
            animationStartTick = compoundTag.contains(ANIMATION_START_TICK_KEY) ? compoundTag.getLong(ANIMATION_START_TICK_KEY) : Long.MIN_VALUE;
        }

        @Override
        public void writeCompoundTag(net.minecraft.nbt.CompoundTag compoundTag) {
            if (animationStartTick != Long.MIN_VALUE) {
                compoundTag.putLong(ANIMATION_START_TICK_KEY, animationStartTick);
            }
        }
    }
}
