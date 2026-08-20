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

    private static TicketSystem.EnumTicketBarrierOpen singleTicketExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, ItemStack singleTicketStack) {
        CompoundTag singleTicketTag = singleTicketStack.getOrCreateTag();
        long enteredStationId = singleTicketTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            int singleTicketFare = singleTicketTag.getInt("fare");
            long expiredTime = singleTicketTag.getLong("expired_time");
            boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
            int fare = getMTRFare(enteredStation.zone, exitStation.zone, isConcessionary);
            if (System.currentTimeMillis() >= expiredTime) {
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_expired");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (fare > singleTicketFare){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_fare");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                Utilities.getInventory(player).removeItem(singleTicketStack);
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
            long expiredTime = octopusTag.getLong("entered_time") + net.hulan.ksd.utils.Utilities.TWO_HOURS;
            boolean isConcessionary = octopusTag.getBoolean("is_concessionary");
            boolean fcValidated = octopusTag.getBoolean("fc_validated");
            int fare = getMTRFare(enteredStation.zone, exitStation.zone, isConcessionary);
            if (System.currentTimeMillis() >= expiredTime) {
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_expired");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (balance < 0){
                playSoundAndSendMessage(world, player.blockPosition(), player, SoundEvents.TICKET_PROCESSOR_FAIL, "gui.ksd.st_insufficient_octopus");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                playSoundAndSendMessage(world, player.blockPosition(), player,
                        false ? SoundEvents.TICKET_BARRIER_CONCESSIONARY : SoundEvents.TICKET_BARRIER,
                        false ? "gui.ksd.st_exit_concessionary" : "gui.ksd.st_exit");
                return TicketSystem.EnumTicketBarrierOpen.OPEN;
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    public static int getMTRFare(int entryZone, int exitZone, boolean isConcessionary) {
        final int fare = BASE_FARE + ZONE_FARE * Math.abs(entryZone - exitZone);
        return isConcessionary ? (int) Math.ceil(fare / 2F) : fare;
    }

    private static void playSoundAndSendMessage(Level world, BlockPos pos, Player player, SoundEvent sound, String message) {
        world.playSound(null, pos, sound, SoundSource.PLAYERS, 1, 1);
        player.displayClientMessage(Text.translatable(message), false);
    }
}
