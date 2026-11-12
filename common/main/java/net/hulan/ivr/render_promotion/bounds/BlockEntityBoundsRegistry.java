package net.hulan.ivr.render_promotion.bounds;

import mtr.block.*;
import net.hulan.ivr.block.*;
import net.hulan.ivr.render_promotion.visibility.BlockEntityVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

public class BlockEntityBoundsRegistry {

    public static void registerBEBounds() {
        MsdBlockEntityBoundsRegistry.register();
        BlockEntityVisibility instance = BlockEntityVisibility.getInstance();
        instance.registerStaticBoundsProvider(BlockKCRClock.TileEntityKCRClock.class,
                entity -> new AABB(entity.getBlockPos()));
        instance.registerStaticBoundsProvider(BlockClassicalSign.TileEntityClassicalSign.class,
                entity -> signBounds(entity, ((BlockClassicalSign) entity.getBlockState().getBlock()).length,
                        ((BlockClassicalSign) entity.getBlockState().getBlock()).getXStart()));
        instance.registerStaticBoundsProvider(BlockModernSign.TileEntityModernSign.class,
                entity -> signBounds(entity, ((BlockModernSign) entity.getBlockState().getBlock()).length,
                        ((BlockModernSign) entity.getBlockState().getBlock()).getXStart()));
        instance.registerStaticBoundsProvider(BlockClassicalSign.TileEntityClassicalSign1Odd.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockModernSign.TileEntityModernSign1Odd.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockKCRStationNameBase.TileEntityKCRStationNameBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.5D, 1.0D));
        instance.registerStaticBoundsProvider(BlockKCRPSDTop.TileEntityKCRRouteBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.0D, 1.0D));
        instance.registerStaticBoundsProvider(BlockKCRRouteSignBase.TileEntityKCRRouteSignBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D, 1.0D, 0.25D));
        instance.registerStaticBoundsProvider(BlockRailwaySign.TileEntityRailwaySign.class,
                entity -> signBounds(entity, ((BlockRailwaySign) entity.getBlockState().getBlock()).length,
                        ((BlockRailwaySign) entity.getBlockState().getBlock()).getXStart()));
        instance.registerStaticBoundsProvider(BlockStationNameBase.TileEntityStationNameBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.5D, 1.0D));
        instance.registerStaticBoundsProvider(BlockRouteSignBase.TileEntityRouteSignBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D, 1.0D, 0.25D));
        instance.registerStaticBoundsProvider(BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.0D, 1.0D));
        instance.registerStaticBoundsProvider(BlockPSDTop.TileEntityPSDTop.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 0.25D, 1.0D));
        instance.registerStaticBoundsProvider(BlockAPGGlass.TileEntityAPGGlass.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.0D, 1.0D));
        instance.registerStaticBoundsProvider(BlockLiftDoor.TileEntityLiftDoor.class,
                entity -> new AABB(entity.getBlockPos()).inflate(1.0D, 1.0D, 1.0D));
        instance.registerBoundsProvider(BlockLiftPanelBase.TileEntityLiftPanel1Base.class,
                BlockEntityBoundsRegistry::liftPanelBounds);
        instance.registerBoundsProvider(BlockLiftButtons.TileEntityLiftButtons.class,
                BlockEntityBoundsRegistry::liftButtonsBounds);
        instance.registerStaticBoundsProvider(BlockClock.TileEntityClock.class,
                entity -> new AABB(entity.getBlockPos()));
        instance.registerStaticBoundsProvider(BlockPIDSBaseHorizontal.TileEntityBlockPIDSBaseHorizontal.class,
                entity -> new AABB(entity.getBlockPos()).inflate(2.0D));
        instance.registerStaticBoundsProvider(BlockPIDSBaseVertical.TileEntityBlockPIDSBaseVertical.class,
                entity -> new AABB(entity.getBlockPos()).inflate(2.0D));
        instance.registerStaticBoundsProvider(BlockArrivalProjectorBase.TileEntityArrivalProjectorBase.class,
                entity -> new AABB(entity.getBlockPos()).inflate(3.0D));
        instance.registerStaticBoundsProvider(BlockNode.TileEntityBoatNode.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.01D));
        instance.registerStaticBoundsProvider(BlockSignalLight2Aspect1.TileEntitySignalLight2Aspect1.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight2Aspect2.TileEntitySignalLight2Aspect2.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight2Aspect3.TileEntitySignalLight2Aspect3.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight2Aspect4.TileEntitySignalLight2Aspect4.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight3Aspect1.TileEntitySignalLight3Aspect1.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight3Aspect2.TileEntitySignalLight3Aspect2.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight4Aspect1.TileEntitySignalLight4Aspect1.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
        instance.registerStaticBoundsProvider(BlockSignalLight4Aspect2.TileEntitySignalLight4Aspect2.class,
                entity -> new AABB(entity.getBlockPos()).inflate(0.25D));
    }

    private static AABB signBounds(BlockEntity entity, int length, int xStart) {
        BlockPos start = entity.getBlockPos();
        Direction facing = entity.getBlockState().getValue(BlockClassicalSign.FACING);
        int middleLength = Math.max((length - (4 - xStart / 4)) / 2, 0);
        BlockPos end = start.relative(facing.getClockWise(), middleLength + 1);
        return new AABB(
                Math.min(start.getX(), end.getX()), start.getY(), Math.min(start.getZ(), end.getZ()),
                Math.max(start.getX(), end.getX()) + 1, start.getY() + 1,
                Math.max(start.getZ(), end.getZ()) + 1);
    }

    private static boolean holdingLiftLinker() {
        net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
        return player != null && mtr.mappings.Utilities.isHolding(player,
                item -> item instanceof mtr.item.ItemLiftButtonsLinkModifier
                        || net.minecraft.world.level.block.Block.byItem(item) instanceof BlockLiftPanelBase
                        || net.minecraft.world.level.block.Block.byItem(item) instanceof BlockLiftButtons);
    }

    private static AABB liftPanelBounds(BlockLiftPanelBase.TileEntityLiftPanel1Base entity) {
        AABB bounds = new AABB(entity.getBlockPos()).inflate(1.0D);
        if (holdingLiftLinker()) {
            BlockPos track = entity.getTrackPosition(entity.getLevel());
            if (track != null) bounds = bounds.minmax(new AABB(track).inflate(0.5D));
        }
        return bounds;
    }

    private static AABB liftButtonsBounds(BlockLiftButtons.TileEntityLiftButtons entity) {
        // MTR caps the combined floor display width at 0.375 blocks.
        AABB[] bounds = {new AABB(entity.getBlockPos()).inflate(0.05D)};
        if (holdingLiftLinker()) {
            entity.forEachTrackPosition(entity.getLevel(), (track, floor) ->
                    bounds[0] = bounds[0].minmax(new AABB(track).inflate(0.5D)));
        }
        return bounds[0];
    }
}
