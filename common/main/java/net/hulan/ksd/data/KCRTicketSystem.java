package net.hulan.ksd.data;

import mtr.SoundEvents;
import mtr.data.TicketSystem;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import net.hulan.ksd.item.ItemOctopus;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

public class KCRTicketSystem {

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    public static TicketSystem.EnumTicketBarrierOpen singleTicketCheck(Level world, Player player, BlockPos pos, ItemStack singleTicketStack, boolean isEntrance) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null && singleTicketStack.getItem() instanceof ItemSingleTicket) {
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                if (isEntrance) {
                    return singleTicketEnter(world, player, currentStation, singleTicketStack);
                } else {
                    return singleTicketExit(world, railwayData, currentStation, player, singleTicketStack);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen octopusCheck(Level world, Player player, BlockPos pos, ItemStack octopusStack, boolean isEntrance) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null && octopusStack.getItem() instanceof ItemOctopus) {
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                if (isEntrance) {
                    return octopusEnter(world, player, currentStation, octopusStack);
                } else {
                    return octopusExit(world, railwayData, currentStation, player, octopusStack);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen singleTicketEnter(Level world, Player player, KSDStation enteredStation, ItemStack singleTicketStack) {
        CompoundTag singleTicketTag = singleTicketStack.getOrCreateTag();
        if (singleTicketTag.contains("entered_station_id")) {
            playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_already_entered");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        singleTicketTag.putLong("entered_station_id", enteredStation.id);
        boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
        playSoundAndSendMessage(world, player.blockPosition(), player,
                isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                isConcessionary ? "gui.ksd.st_enter_concessionary" : "gui.ksd.st_enter");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen octopusEnter(Level world, Player player, KSDStation enteredStation, ItemStack octopusStack) {
        CompoundTag octopusTag = octopusStack.getOrCreateTag();
        if (octopusTag.contains("entered_station_id") && octopusTag.contains("entered_time")) {
            playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_already_entered");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        octopusTag.putLong("entered_station_id", enteredStation.id);
        octopusTag.putLong("entered_time", System.currentTimeMillis());
        boolean isConcessionary = false;
        playSoundAndSendMessage(world, player.blockPosition(), player,
                isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                isConcessionary ? "gui.ksd.st_enter_concessionary" : "gui.ksd.st_enter");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen singleTicketExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, ItemStack singleTicketItem) {
        CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
        long enteredStationId = singleTicketTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            int singleTicketFare = singleTicketTag.getInt("fare");
            long expiredTime = singleTicketTag.getLong("expired_time");
            boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
            boolean fcAvailable = singleTicketTag.getBoolean("fc_available");
            int fare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcAvailable);
            if (System.currentTimeMillis() >= expiredTime) {
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_expired");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (fare > singleTicketFare){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_fare");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                if (fcAvailable) {
                    FirstClassValidationSystem.ticketDevalidate(player);
                }
                Utilities.getInventory(player).removeItem(singleTicketItem);
                playSoundAndSendMessage(world, player.blockPosition(), player,
                        isConcessionary ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                        isConcessionary ? "gui.ksd.st_exit_concessionary" : "gui.ksd.st_exit");
                return TicketSystem.EnumTicketBarrierOpen.OPEN;
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen octopusExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, ItemStack octopusStack) {
        CompoundTag octopusTag = octopusStack.getOrCreateTag();
        long enteredStationId = octopusTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            int balance = octopusTag.getInt("balance");
            long expiredTime = octopusTag.getLong("entered_time") + net.hulan.ksd.utils.Utilities.EXPIRED_TIME;
            boolean isConcessionary = octopusTag.getBoolean("is_concessionary");
            boolean fcValidated = octopusTag.getBoolean("fc_validated");
            int fare = getFare(railwayData.dataCache.wayFinder, enteredStation, exitStation, isConcessionary, fcValidated);
            if (System.currentTimeMillis() >= expiredTime) {
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_expired");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (balance < 0){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_octopus");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                int newBalance = balance - fare;
                octopusTag.putLong("balance", newBalance);
                octopusTag.remove("entered_station_id");
                octopusTag.remove("entered_time");
                playSoundAndSendMessage(world, player.blockPosition(), player,
                        false ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                        false ? "gui.ksd.st_exit_concessionary" : "gui.ksd.st_exit");
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
