package net.hulan.ksd.data;

import mtr.SoundEvents;
import mtr.data.TicketSystem;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class KCRTicketSystem {

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    public static TicketSystem.EnumTicketBarrierOpen singleTicketCheck(Level world, Player player, BlockPos pos, ItemStack singleTicketStack, boolean isEntrance) {
        KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
        if (railwayData != null && singleTicketStack.getItem() instanceof ItemSingleTicket) {
            CompoundTag singleTicketTag = singleTicketStack.getOrCreateTag();
            KSDStation currentStation = KSDRailwayData.getStation(railwayData.stations, pos);
            if (currentStation != null) {
                if (isEntrance) {
                    return singleTicketEnter(world, player, currentStation, singleTicketTag);
                } else {
                    return singleTicketExit(world, railwayData, currentStation, player, singleTicketTag);
                }
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    private static TicketSystem.EnumTicketBarrierOpen singleTicketEnter(Level world, Player player, KSDStation enteredStation, CompoundTag singleTicketTag) {
        if (singleTicketTag.getLong("entered_station_id") != 0L) {
            playSoundAndSendMessage(world, player.blockPosition(), player, "4");
            return TicketSystem.EnumTicketBarrierOpen.CLOSED;
        }
        singleTicketTag.putLong("entered_station_id", enteredStation.id);
        playSoundAndSendMessage(world, player.blockPosition(), player, "1");
        return TicketSystem.EnumTicketBarrierOpen.OPEN;
    }

    private static TicketSystem.EnumTicketBarrierOpen singleTicketExit(Level world, KSDRailwayData railwayData, KSDStation exitStation, Player player, CompoundTag singleTicketTag) {
        long enteredStationId = singleTicketTag.getLong("entered_station_id");
        KSDStation enteredStation = DataUtilities.getStation(railwayData.stations, enteredStationId);
        if (enteredStation != null) {
            int singleTicketFare = singleTicketTag.getInt("fare");
            long expiredTime = singleTicketTag.getLong("expired_time");
            boolean isConcessionary = singleTicketTag.getBoolean("is_concessionary");
            int fare = getMTRFare(enteredStation.zone, exitStation.zone, isConcessionary);
            if (System.currentTimeMillis() >= expiredTime) {
                playSoundAndSendMessage(world, player.blockPosition(), player, "3");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else if (fare > singleTicketFare){
                playSoundAndSendMessage(world, player.blockPosition(), player, "2");
                return TicketSystem.EnumTicketBarrierOpen.CLOSED;
            } else {
                ContainerHelper.clearOrCountMatchingItems(Utilities.getInventory(player), (itemStack) -> itemStack.getItem() == KSDItems.SINGLE_TICKET.get(), 1, false);
                playSoundAndSendMessage(world, player.blockPosition(), player, "1");
                return TicketSystem.EnumTicketBarrierOpen.OPEN;
            }
        }
        return TicketSystem.EnumTicketBarrierOpen.CLOSED;
    }

    public static int getMTRFare(int entryZone, int exitZone, boolean isConcessionary) {
        final int fare = BASE_FARE + ZONE_FARE * Math.abs(entryZone - exitZone);
        return isConcessionary ? (int) Math.ceil(fare / 2F) : fare;
    }

    private static void playSoundAndSendMessage(Level world, BlockPos pos, Player player, String message) {
        world.playSound(null, pos, SoundEvents.TICKET_BARRIER, SoundSource.PLAYERS, 1, 1);
        player.displayClientMessage(Text.translatable(message), false);
    }
}
