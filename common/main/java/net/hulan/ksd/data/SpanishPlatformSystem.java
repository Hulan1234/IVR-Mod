package net.hulan.ksd.data;

import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPlatform;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class SpanishPlatformSystem {

    private static final int MAX_ENTRIES = 512;

    private SpanishPlatformSystem() {
    }

    public static final class Cache {

        private long tick = Long.MIN_VALUE;
        private Object world;
        private final Map<Key, ScanResult> entries = new HashMap<>();
    }

    public static final class ScanResult {

        private boolean hasPlatform;
        private BlockPos[] blocks;

        public boolean hasPlatform() {
            return hasPlatform;
        }
    }

    @FunctionalInterface
    public interface SkipCheck {

        boolean skip(Level world, double x, double y, double z);
    }

    @FunctionalInterface
    public interface DoorOpener {

        void open(Block block, BlockPos pos, int dwellTicks);
    }

    public static ScanResult scan(Cache cache,
                                  Level world,
                                  long platformId,
                                  boolean collectBlocks,
                                  double trainX,
                                  double trainY,
                                  double trainZ,
                                  float checkYaw,
                                  float pitch,
                                  double halfSpacing,
                                  SkipCheck skip) {
        long tick = world.getGameTime();
        if (cache.tick != tick || cache.world != world) {
            cache.entries.clear();
            cache.tick = tick;
            cache.world = world;
        }
        Key key = new Key(platformId, collectBlocks, trainX, trainY, trainZ, checkYaw, pitch, halfSpacing);
        ScanResult cached = cache.entries.get(key);
        if (cached != null) {
            return cached;
        }
        if (cache.entries.size() >= MAX_ENTRIES) {
            cache.entries.clear();
        }
        ScanResult result = new ScanResult();
        if (!skip.skip(world, trainX, trainY, trainZ)) {
            Vec3 offsetVec = new Vec3(1.0D, 0.0D, 0.0D).yRot(checkYaw).xRot(pitch);
            Vec3 traverseVec = new Vec3(0.0D, 0.0D, 1.0D).yRot(checkYaw).xRot(pitch);
            BlockPos[] found = new BlockPos[8];
            int count = 0;
            for (int checkX = 1; checkX <= 3; ++checkX) {
                for (int checkY = -2; checkY <= 3; ++checkY) {
                    for (double checkZ = -halfSpacing; checkZ <= halfSpacing; ++checkZ) {
                        BlockPos checkPos = RailwayData.newBlockPos(
                                trainX + offsetVec.x * (double) checkX + traverseVec.x * checkZ,
                                trainY + (double) checkY,
                                trainZ + offsetVec.z * (double) checkX + traverseVec.z * checkZ);
                        Block block = world.getBlockState(checkPos).getBlock();
                        if (block instanceof BlockPlatform || block instanceof BlockPSDAPGBase) {
                            result.hasPlatform = true;
                            if (collectBlocks) {
                                if (count == found.length) {
                                    BlockPos[] grown = new BlockPos[found.length * 2];
                                    System.arraycopy(found, 0, grown, 0, found.length);
                                    found = grown;
                                }
                                found[count++] = checkPos;
                            }
                        }
                    }
                }
            }
            if (count > 0) {
                result.blocks = new BlockPos[count];
                System.arraycopy(found, 0, result.blocks, 0, count);
            }
        }
        cache.entries.put(key, result);
        return result;
    }

    public static void open(Level world, ScanResult result, int dwellTicks, DoorOpener opener) {
        BlockPos[] blocks = result == null ? null : result.blocks;
        if (blocks == null) {
            return;
        }
        for (BlockPos checkPos : blocks) {
            Block block = world.getBlockState(checkPos).getBlock();
            if (block instanceof BlockPlatform || block instanceof BlockPSDAPGBase) {
                opener.open(block, checkPos, dwellTicks);
            }
        }
    }

    private record Key(long platformId,
                       boolean collectBlocks,
                       double x,
                       double y,
                       double z,
                       float yaw,
                       float pitch,
                       double halfSpacing) {
    }
}
