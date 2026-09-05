package net.hulan.ksd.data;

import mtr.SoundEvents;
import mtr.data.TicketSystem;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public class KCRTicketSystem {

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    public static TicketSystem.EnumTicketBarrierOpen singleTicketCheck(Level world,
                                                                       Player player,
                                                                       BlockPos pos,
                                                                       ItemStack stStack,
                                                                       boolean canEnter,
                                                                       boolean canExit) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null) {
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                CompoundTag stTag = stStack.getOrCreateTag();
                boolean isEntrance;
                if (canEnter && canExit) {
                    isEntrance = !stTag.contains("entered_station_id");
                } else {
                    isEntrance = canEnter;
                }
                if (isEntrance) {
                    return stEnter(world, player, currentStation, stTag);
                } else {
                    return stExit(world, railwayData, currentStation, player, stStack);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen stEnter(Level world, Player player, KSDStation enteredStation, CompoundTag stTag) {
        if (stTag.contains("entered_station_id")) {
            playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.already_entered");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        stTag.putLong("entered_station_id", enteredStation.id);
        boolean isConcessionary = stTag.getBoolean("is_concessionary");
        playSoundAndSendMessage(world, player.blockPosition(), player,
                isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                isConcessionary ? "gui.ksd.enter_concessionary" : "gui.ksd.enter");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen stExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, ItemStack stItem) {
        CompoundTag stTag = stItem.getOrCreateTag();
        long enteredStationId = stTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            int stFare = stTag.getInt("fare");
            long expireTime = stTag.getLong("expire_time");
            boolean isConcessionary = stTag.getBoolean("is_concessionary");
            boolean fcAvailable = stTag.getBoolean("fc_available");
            int actualFare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcAvailable);
            if (isExpired(expireTime)) {
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.expired");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (actualFare > stFare){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_fare");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                if (fcAvailable) {
                    FirstClassValidationSystem.ticketDevalidate(player);
                }
                Utilities.getInventory(player).removeItem(stItem);
                playSoundAndSendMessage(world, player.blockPosition(), player,
                        isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                        isConcessionary ? "gui.ksd.exit_concessionary" : "gui.ksd.exit");
                return TicketSystem.EnumTicketBarrierOpen.OPEN;
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    public static TicketSystem.EnumTicketBarrierOpen octopusCheck(Level world,
                                                                  Player player,
                                                                  BlockPos pos,
                                                                  ItemStack octopusStack,
                                                                  boolean canEnter,
                                                                  boolean canExit) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null) {
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                CompoundTag octopusTag = octopusStack.getOrCreateTag();
                boolean isEntrance;
                if (canEnter && canExit) {
                    isEntrance = !octopusTag.contains("entered_station_id");
                } else {
                    isEntrance = canEnter;
                }
                if (isEntrance) {
                    return octopusEnter(world, player, currentStation, octopusTag);
                } else {
                    return octopusExit(world, railwayData, currentStation, player, octopusTag);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen octopusEnter(Level world, Player player, KSDStation enteredStation, CompoundTag octopusTag) {
        if (octopusTag.contains("entered_station_id") && octopusTag.contains("entered_time")) {
            playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.already_entered");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        octopusTag.putLong("entered_station_id", enteredStation.id);
        octopusTag.putLong("entered_time", System.currentTimeMillis());
        boolean isConcessionary = octopusTag.getBoolean("is_concessionary");
        playSoundAndSendMessage(world, player.blockPosition(), player,
                isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                isConcessionary ? "gui.ksd.enter_concessionary" : "gui.ksd.enter");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen octopusExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, CompoundTag octopusTag) {
        long enteredStationId = octopusTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            UUID uuid = octopusTag.getUUID("uuid");
            int balance = octopusTag.getInt("balance");
            long expireTime = octopusTag.getLong("entered_time") + net.hulan.ksd.utils.Utilities.EXPIRE_TIME;
            boolean isConcessionary = octopusTag.getBoolean("is_concessionary");
            boolean fcValidated = octopusTag.getBoolean("fc_validated");
            int fare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcValidated);
            if (isExpired(expireTime)) {
                fare += getExpiredFare(expireTime);
            }
            if (balance < 0){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_octopus");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                OctopusSystem.addValue(uuid, -fare, Octopus.History.Source.MTR, railwayData.jsonDataManager, Utilities.getInventory(player));
                octopusTag.remove("entered_station_id");
                octopusTag.remove("entered_time");
                playSoundAndSendMessage(world, player.blockPosition(), player,
                        isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                        isConcessionary ? "gui.ksd.exit_concessionary" : "gui.ksd.exit");
                return TicketSystem.EnumTicketBarrierOpen.OPEN;
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    public static int getFare(WayFinder wayFinder, KSDStation from, KSDStation to, boolean isConcessionary, boolean fcAvailable) {
        List<WayFinder.RouteSegment> segments = wayFinder.findBestRoute(from, to);
        int totalFare = BASE_FARE;
        for (WayFinder.RouteSegment segment : segments) {
            int part = getZoneFare(segment.from().zone, segment.to().zone) * (segment.hasFCService() && fcAvailable ? 2 : 1);
            totalFare += part;
        }
        return totalFare / (isConcessionary ? 2 : 1);
    }

    public static boolean isExpired(long expiredTime) {
        return System.currentTimeMillis() - expiredTime > 0;
    }

    public static long newExpireTime() {
        return System.currentTimeMillis() + net.hulan.ksd.utils.Utilities.EXPIRE_TIME;
    }

    public static int getExpiredFare(long expireTime) {
        double minutes = (System.currentTimeMillis() - expireTime) / 60_000.0F;
        return (int) Math.ceil(2 * (minutes / 60));
    }

    public static int getEmeraldCount(int count) {
        return (int) Math.ceil((double) count / 16);
    }

    private static int getZoneFare(int entryZone, int exitZone) {
        return ZONE_FARE * Math.abs(entryZone - exitZone);
    }

    private static void playSoundAndSendMessage(Level world, BlockPos pos, Player player, SoundEvent sound, String message) {
        world.playSound(null, pos, sound, SoundSource.PLAYERS, 1, 1);
        player.displayClientMessage(Text.translatable(message), false);
    }
}
