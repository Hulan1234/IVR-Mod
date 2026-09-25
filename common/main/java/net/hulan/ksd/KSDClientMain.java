package net.hulan.ksd;

import mtr.RegistryClient;
import mtr.data.EnumHelper;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.SingleTicketSystem;
import net.hulan.ksd.packet.KSDPacket;
import net.hulan.ksd.packet.KSDPacketClient;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public class KSDClientMain implements KSDPacket {

    public static void init() {
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_KSD_DASHBOARD_SCREEN,
                (packet) -> KSDPacketClient.openKSDDashboardScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_TICKETS_SCREEN,
                (packet) -> KSDPacketClient.openTicketsScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_FARE_ADJUSTMENT_SCREEN,
                (packet) -> KSDPacketClient.openFareAdjustmentsScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_KCR_ST_MACHINE_SCREEN,
                (packet) -> KSDPacketClient.openKCRSTMachineScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_APPLY_OCTOPUS_SCREEN,
                (packet) -> KSDPacketClient.openPurchaseOctopusScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_OPEN_ADD_VALUE_SCREEN,
                (packet) -> KSDPacketClient.openAddValueMachineScreenS2C(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(
                KSD_PACKET_CHUNK_S2C,
                (packet) -> KSDPacketClient.receiveChunk(Minecraft.getInstance(), packet));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_UPDATE_STATION, (packet) -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.STATIONS,
                KSDClientData.DATA_CACHE.stationIdMap,
                KSDStation::new,
                false));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_UPDATE_PLATFORM, packet -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.PLATFORMS,
                KSDClientData.DATA_CACHE.platformIdMap,
                null,
                false));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_UPDATE_ROUTE, packet -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.ROUTES,
                KSDClientData.DATA_CACHE.routeIdMap,
                KSDRoute::new,
                false));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_DELETE_STATION, (packet) -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.STATIONS,
                KSDClientData.DATA_CACHE.stationIdMap,
                KSDStation::new,
                true));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_DELETE_PLATFORM, packet -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.PLATFORMS,
                KSDClientData.DATA_CACHE.platformIdMap,
                null,
                true));
        RegistryClient.registerNetworkReceiver(KSD_PACKET_DELETE_ROUTE, packet -> KSDPacketClient.receiveUpdateOrDeleteS2C(
                Minecraft.getInstance(),
                packet,
                KSDClientData.ROUTES,
                KSDClientData.DATA_CACHE.routeIdMap,
                KSDRoute::new,
                true));
        Utilities.getInstance().registerTicketItemModelPredicator(
                KSDItems.SINGLE_TICKET.get(),
                new ResourceLocation(KSDMain.MOD_ID, "ticket_variant"),
                (ticketItem, world, entity) -> {
                    CompoundTag ticketTag = ticketItem.getOrCreateTag();
                    SingleTicketSystem.TicketType ticketType = EnumHelper.valueOf(
                            SingleTicketSystem.TicketType.MTR,
                            ticketTag.getString("ticket_type"));
                    boolean isConcessionary = ticketTag.getBoolean("is_concessionary");
                    boolean fcAvailable = ticketTag.getBoolean("fc_available");
                    return ticketType.getModelPredicateValue(isConcessionary, fcAvailable);
                });
    }
}
