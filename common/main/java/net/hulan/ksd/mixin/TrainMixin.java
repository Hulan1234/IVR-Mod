package net.hulan.ksd.mixin;

import mtr.data.Depot;
import mtr.data.Train;
import mtr.path.PathData;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.SpanishPlatformSystem;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Train.class)
public abstract class TrainMixin {

    @Shadow(remap = false)
    protected float elapsedDwellTicks;

    @Shadow(remap = false)
    public abstract int getTotalDwellTicks();

    @Shadow(remap = false)
    @Final
    public static int DOOR_MOVE_TIME;

    @Shadow(remap = false)
    @Final
    protected static int DOOR_DELAY;

    @Shadow(remap = false)
    @Final
    public List<PathData> path;

    @Shadow(remap = false)
    protected int nextPlatformIndex;

    @Shadow(remap = false)
    protected boolean doorTarget;

    /** Per-train memo table for door scans; see {@link SpanishPlatformSystem}. */
    @Unique
    private SpanishPlatformSystem.Cache ivr$scanCache;

    @Unique
    private SpanishPlatformSystem.Cache iVR$scanCache() {
        if (ivr$scanCache == null) {
            ivr$scanCache = new SpanishPlatformSystem.Cache();
        }
        return ivr$scanCache;
    }

    @Redirect(method = "calculateCar", at = @At(value = "INVOKE", target = "Lmtr/data/Train;scanDoors(Lnet/minecraft/world/level/Level;DDDFFDI)Z", ordinal = 0))
    private boolean scanLeftDoor(Train instance,
                                 Level world,
                                 double trainX,
                                 double trainY,
                                 double trainZ,
                                 float checkYaw,
                                 float pitch,
                                 double halfSpacing,
                                 int dwellTicks) {
        return iVR$spanishCheck((TrainInvoker) instance, world, trainX, trainY, trainZ, checkYaw, pitch, halfSpacing, dwellTicks, KSDPlatform.DoorOpeningSide.LEFT);
    }

    @Redirect(method = "calculateCar", at = @At(value = "INVOKE", target = "Lmtr/data/Train;scanDoors(Lnet/minecraft/world/level/Level;DDDFFDI)Z", ordinal = 1))
    private boolean scanRightDoor(Train instance,
                                  Level world,
                                  double trainX,
                                  double trainY,
                                  double trainZ,
                                  float checkYaw,
                                  float pitch,
                                  double halfSpacing,
                                  int dwellTicks) {
        return iVR$spanishCheck((TrainInvoker) instance, world, trainX, trainY, trainZ, checkYaw, pitch, halfSpacing, dwellTicks, KSDPlatform.DoorOpeningSide.RIGHT);
    }

    @Inject(method = "simulateTrain",
            at = @At(value = "FIELD",
                     target = "Lmtr/data/Train;doorTarget:Z",
                     shift = At.Shift.AFTER,
                     ordinal = 1,
                     opcode = Opcodes.GETFIELD),
            remap = false)
    private void tempDoorOpen(Level world, float ticksElapsed, Depot depot, CallbackInfo ci) {
        doorTarget = iVR$openDoors(world, doorTarget);
    }

    @Unique
    private boolean iVR$spanishCheck(TrainInvoker invoker,
                                     Level world,
                                     double trainX,
                                     double trainY,
                                     double trainZ,
                                     float checkYaw,
                                     float pitch,
                                     double halfSpacing,
                                     int dwellTicks,
                                     KSDPlatform.DoorOpeningSide leftOrRight) {
        long platformId = path.get(nextPlatformIndex).savedRailBaseId;
        KSDPlatform platform = iVR$getPlatform(world, platformId);
        boolean spanish = platform != null && platform.isSpanishPlatform
                && !platform.doorOpeningSide.equals(KSDPlatform.DoorOpeningSide.DEFAULT);
        SpanishPlatformSystem.ScanResult entry = SpanishPlatformSystem.scan(
                iVR$scanCache(), world, platformId, spanish,
                trainX, trainY, trainZ, checkYaw, pitch, halfSpacing,
                invoker::invokeSkipScanBlocks);
        if (!spanish) {
            return entry.hasPlatform();
        }
        float phase = (float) getTotalDwellTicks() / 2;
        boolean spanishCheck = platform.doorOpeningSide.equals(leftOrRight);
        if (phase + DOOR_DELAY <= elapsedDwellTicks && elapsedDwellTicks < phase * 2) {
            spanishCheck = !spanishCheck;
        }
        boolean scanDoor = entry.hasPlatform() && spanishCheck;
        if (scanDoor) {
            SpanishPlatformSystem.open(world, entry, dwellTicks,
                    (block, pos, ticks) -> invoker.invokeOpenDoors(world, block, pos, ticks));
        }
        return scanDoor;
    }

    @Unique
    private boolean iVR$openDoors(Level world, boolean original) {
        long platformId = path.get(nextPlatformIndex).savedRailBaseId;
        KSDPlatform platform = iVR$getPlatform(world, platformId);
        if (platform == null || !platform.isSpanishPlatform || platform.doorOpeningSide.equals(KSDPlatform.DoorOpeningSide.DEFAULT)) {
            return original;
        }
        float phase = (float) getTotalDwellTicks() / 2;
        if (0 <= elapsedDwellTicks && elapsedDwellTicks < DOOR_DELAY) {
            return false;
        } else if (DOOR_DELAY <= elapsedDwellTicks && elapsedDwellTicks < phase - DOOR_MOVE_TIME) {
            return true;
        } else if (phase - DOOR_MOVE_TIME <= elapsedDwellTicks && elapsedDwellTicks < phase + DOOR_DELAY) {
            return false;
        } else if (phase + DOOR_DELAY <= elapsedDwellTicks && elapsedDwellTicks < phase * 2 - DOOR_MOVE_TIME) {
            return true;
        } else if (phase * 2 - DOOR_MOVE_TIME <= elapsedDwellTicks && elapsedDwellTicks < phase * 2) {
            return false;
        } else {
            return original;
        }
    }

    @Unique
    private KSDPlatform iVR$getPlatform(Level world, long platformId) {
        if (world.isClientSide) {
            return DataUtilities.getPlatform(KSDClientData.PLATFORMS, platformId);
        } else {
            KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
            if (railwayData == null) {
                return null;
            }
            return DataUtilities.getPlatform(railwayData.platforms, platformId);
        }
    }
}
