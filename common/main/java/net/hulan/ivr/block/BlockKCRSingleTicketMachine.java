package net.hulan.ivr.block;

import mtr.block.IBlock;
import mtr.mappings.*;
import net.hulan.ivr.IVRBlockEntityTypes;
import net.hulan.ksd.packet.KSDPacketServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class BlockKCRSingleTicketMachine extends BlockDirectionalMapper implements EntityBlockMapper {

    private static final BooleanProperty STORED = BooleanProperty.create("stored");

    public BlockKCRSingleTicketMachine() {
        super(BlockBehaviour.Properties.of(Material.METAL, MaterialColor.COLOR_GRAY)
                .requiresCorrectToolForDrops()
                .strength(2)
                .lightLevel(state -> 5));
        registerDefaultState(defaultBlockState().setValue(STORED, false));
    }

    @SuppressWarnings("deprecation")
    public @NotNull InteractionResult use(BlockState blockState, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        boolean stored = IBlock.getStatePropertySafe(blockState, STORED);
        if (stored) {
            BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof TileEntityKCRSingleTicketMachine stmEntity) {
                stmEntity.releaseItems(Utilities.getInventory(player));
                world.setBlock(pos, blockState.setValue(STORED, false), 3);
            }
        } else {
            if (!world.isClientSide) {
                KSDPacketServer.openKCRTicketMachineScreenS2C((ServerPlayer) player, pos);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @SuppressWarnings("deprecation")
    public @NotNull VoxelShape getShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext) {
        Direction facing = IBlock.getStatePropertySafe(blockState, FACING);
        return IBlock.getVoxelShapeByDirection(0 ,0, 0, 32, 32, 16, facing.getCounterClockWise());
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STORED);
    }

    @Override
    public BlockEntityMapper createBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new TileEntityKCRSingleTicketMachine(blockPos, blockState);
    }

    public static class TileEntityKCRSingleTicketMachine extends BlockEntityClientSerializableMapper {

        private final List<ItemStack> items = new ArrayList<>();

        public TileEntityKCRSingleTicketMachine(BlockPos blockPos, BlockState blockState) {
            super(IVRBlockEntityTypes.KCR_SINGLE_TICKET_MACHINE_TILE_ENTITY.get(), blockPos, blockState);
        }

        public void storeItems(List<ItemStack> items) {
            this.items.clear();
            this.items.addAll(items);
            if (level != null && getBlockState() != null) {
                level.setBlock(getBlockPos(), getBlockState().setValue(STORED, true), 3);
            }
            setChanged();
            syncData();
        }

        public void releaseItems(Inventory inventory) {
            for (ItemStack item : items) {
                inventory.add(item.copy());
            }
            items.clear();
            setChanged();
            syncData();
        }

        public void readCompoundTag(CompoundTag compoundTag) {
            ListTag listTag = compoundTag.getList("items", 10);
            for (int i = 0; i < listTag.size(); ++i) {
                items.add(ItemStack.of(listTag.getCompound(i)));
            }
        }

        public void writeCompoundTag(CompoundTag compoundTag) {
            ListTag listTag = new ListTag();
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    CompoundTag tag = new CompoundTag();
                    stack.save(tag);
                    listTag.add(tag);
                }
            }
            compoundTag.put("items", listTag);
        }
    }
}
