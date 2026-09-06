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
import java.util.Set;
import java.util.UUID;

public class KCRTicketSystem {

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    public static TicketSystem.EnumTicketBarrierOpen check(Level world,
                                                           Player player,
                                                           BlockPos pos,
                                                           ItemStack item,
                                                           boolean canEnter,
                                                           boolean canExit,
                                                           boolean isOctopus) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null) {
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                CompoundTag itemTag = item.getOrCreateTag();
                boolean isEntrance;
                if (canEnter && canExit) {
                    isEntrance = isEntered(itemTag, railwayData.stations, isOctopus);
                } else {
                    isEntrance = canEnter;
                }
                if (isEntrance) {
                    return onEnter(world, railwayData, player, currentStation, itemTag, isOctopus);
                } else {
                    return onExit(world, railwayData, player, currentStation, item, isOctopus);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen onEnter(Level world,
                                                              KSDRailwayData railwayData,
                                                              Player player,
                                                              KSDStation enteredStation,
                                                              CompoundTag itemTag,
                                                              boolean isOctopus) {
        if (isEntered(itemTag, railwayData.stations, isOctopus)) {
            playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.already_entered");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        enterStation(itemTag, enteredStation.id, isOctopus);
        boolean isConcessionary = itemTag.getBoolean("is_concessionary");
        playSoundAndSendMessage(world, player.blockPosition(), player,
                isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                isConcessionary ? "gui.ksd.enter_concessionary" : "gui.ksd.enter");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen onExit(Level world,
                                                             KSDRailwayData railwayData,
                                                             Player player,
                                                             KSDStation exitStation,
                                                             ItemStack item,
                                                             boolean isOctopus) {
        CompoundTag itemTag = item.getOrCreateTag();
        KSDStation enteredStation = getEnteredStation(itemTag, railwayData.stations);
        if (enteredStation != null) {
            if (!isOctopus) {
                int stFare = itemTag.getInt("fare");
                long expireTime = itemTag.getLong("expire_time");
                boolean isConcessionary = itemTag.getBoolean("is_concessionary");
                boolean fcAvailable = itemTag.getBoolean("fc_available");
                int actualFare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcAvailable);
                if (isExpired(expireTime)) {
                    playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.expired");
                    return TicketSystem.EnumTicketBarrierOpen.CLOSED;
                } else if (actualFare > stFare){
                    playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_fare");
                    return TicketSystem.EnumTicketBarrierOpen.CLOSED;
                } else {
                    exitStation(itemTag, false);
                    if (fcAvailable) {
                        FirstClassValidationSystem.devalidate(player);
                    }
                    Utilities.getInventory(player).removeItem(item);
                    playSoundAndSendMessage(world, player.blockPosition(), player,
                            isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                            isConcessionary ? "gui.ksd.exit_concessionary" : "gui.ksd.exit");
                    return TicketSystem.EnumTicketBarrierOpen.OPEN;
                }
            } else {
                UUID uuid = itemTag.getUUID("uuid");
                int balance = itemTag.getInt("balance");
                long expireTime = getEntryTime(itemTag) + net.hulan.ksd.utils.Utilities.EXPIRE_TIME;
                boolean isConcessionary = itemTag.getBoolean("is_concessionary");
                boolean fcValidated = FirstClassValidationSystem.isValidated(player);
                int fare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcValidated);
                if (isExpired(expireTime)) {
                    playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.expired");
                    return TicketSystem.EnumTicketBarrierOpen.CLOSED;
                } else if (balance < 0){
                    playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_octopus");
                    return TicketSystem.EnumTicketBarrierOpen.CLOSED;
                } else {
                    exitStation(itemTag, true);
                    if (fcValidated) {
                        FirstClassValidationSystem.devalidate(player);
                    }
                    OctopusSystem.addValue(uuid, -fare, Octopus.History.Source.MTR, railwayData.jsonDataManager, Utilities.getInventory(player));
                    playSoundAndSendMessage(world, player.blockPosition(), player,
                            isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                            isConcessionary ? "gui.ksd.exit_concessionary" : "gui.ksd.exit");
                    return TicketSystem.EnumTicketBarrierOpen.OPEN;
                }
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

    public static void enterStation(CompoundTag itemTag, long enteredStationId, boolean isOctopus) {
        itemTag.putLong("entered_station_id", enteredStationId);
        if (isOctopus) {
            itemTag.putLong("entry_time", System.currentTimeMillis());
        }
    }

    public static KSDStation getEnteredStation(CompoundTag itemTag, Set<KSDStation> stations) {
        long enteredStationId = itemTag.getLong("entered_station_id");
        return DataUtilities.getStation(stations, enteredStationId);
    }

    public static long getEntryTime(CompoundTag itemTag) {
        return itemTag.getLong("entry_time");
    }

    public static boolean isEntered(CompoundTag itemTag, Set<KSDStation> stations, boolean isOctopus) {
        return getEnteredStation(itemTag, stations) != null && (!isOctopus || itemTag.contains("entry_time"));
    }

    public static void exitStation(CompoundTag itemTag, boolean isOctopus) {
        itemTag.remove("entered_station_id");
        if (isOctopus) {
            itemTag.remove("entry_time");
        }
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
