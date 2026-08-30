package net.hulan.ksd.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.data.EnumHelper;
import mtr.data.NameColorDataBase;
import mtr.data.TransportMode;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataBase;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.PaymentMethod;
import net.hulan.ksd.sreen.KCRSingleTicketMachineScreen;
import net.hulan.ksd.sreen.KSDDashboardScreen;
import net.hulan.ksd.sreen.PutItemScreen;
import net.hulan.ksd.sreen.SingleTicketFareAdjustmentScreen;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public class KSDPacketClient extends PacketTrainDataBase implements KSDPacket {

    private static final Map<Integer, ByteBuf> TEMP_PACKETS_RECEIVER = new HashMap<>();
    private static long tempPacketId = 0L;
    private static int expectedSize = 0;

    public static void openKSDDashboardScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        TransportMode transportMode = EnumHelper.valueOf(TransportMode.TRAIN, packet.readUtf());
        boolean useTimeAndWindSync = packet.readBoolean();
        minecraftClient.execute(() -> {
            if (!(minecraftClient.screen instanceof KSDDashboardScreen)) {
                UtilitiesClient.setScreen(minecraftClient, new KSDDashboardScreen(transportMode, useTimeAndWindSync));
            }
        });
    }

    public static void openKCRSingleTicketMachineScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        KCRSingleTicketMachineScreen.RailMapType railMapType = EnumHelper.valueOf(KCRSingleTicketMachineScreen.RailMapType.MTR, packet.readUtf());
        BlockPos storeBlockPos = packet.readBlockPos();
        int balance = packet.readInt();
        minecraftClient.execute(() -> {
            KSDStation current = KSDRailwayData.getStation(KSDClientData.STATIONS, storeBlockPos);
            if (!(minecraftClient.screen instanceof KCRSingleTicketMachineScreen) &&
                    current != null) {
                UtilitiesClient.setScreen(minecraftClient, new KCRSingleTicketMachineScreen(
                        railMapType,
                        current,
                        storeBlockPos,
                        balance));
            }
        });
    }

    public static void openKCRSingleTicketFareAdjustmentScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        BlockPos storeBlockPos = packet.readBlockPos();
        int balance = packet.readInt();
        minecraftClient.execute(() -> {
            if (!(minecraftClient.screen instanceof KCRSingleTicketMachineScreen)) {
                UtilitiesClient.setScreen(minecraftClient, new PutItemScreen(
                        "item.ksd.single_ticket",
                        KSDItems.SINGLE_TICKET.get(),
                        PutItemScreen.PutMethod.PUT,
                        true,
                        (singleTicketItem, amount) -> {
                            CompoundTag singleTicketTag = singleTicketItem.getOrCreateTag();
                            KSDStation current = DataUtilities.getStation(KSDClientData.STATIONS, singleTicketTag.getLong("entered_station_id"));
                            KSDStation destination = KSDRailwayData.getStation(KSDClientData.STATIONS, storeBlockPos);
                            if (!(minecraftClient.screen instanceof SingleTicketFareAdjustmentScreen) &&
                                    current != null &&
                                    destination != null) {
                                UtilitiesClient.setScreen(minecraftClient, new SingleTicketFareAdjustmentScreen(
                                        current,
                                        destination,
                                        balance,
                                        singleTicketItem,
                                        storeBlockPos));
                            }
                        }));
            }
        });
    }

    public static void receiveChunk(Minecraft minecraftClient, FriendlyByteBuf packet) {
        long id = packet.readLong();
        int chunk = packet.readInt();
        boolean complete = packet.readBoolean();
        if (tempPacketId != id) {
            TEMP_PACKETS_RECEIVER.clear();
            tempPacketId = id;
            expectedSize = Integer.MAX_VALUE;
        }
        if (complete) {
            expectedSize = chunk + 1;
        }
        TEMP_PACKETS_RECEIVER.put(chunk, packet.readBytes(packet.readableBytes()));
        if (TEMP_PACKETS_RECEIVER.size() == expectedSize) {
            FriendlyByteBuf newPacket = new FriendlyByteBuf(Unpooled.buffer());
            for(int i = 0; i < expectedSize; ++i) {
                newPacket.writeBytes(TEMP_PACKETS_RECEIVER.get(i));
            }
            TEMP_PACKETS_RECEIVER.clear();
            try {
                minecraftClient.execute(() -> KSDClientData.receivePacket(newPacket));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static <T extends NameColorDataBase> void receiveUpdateOrDeleteS2C(Minecraft minecraftClient, FriendlyByteBuf packet, Set<T> dataSet, Map<Long, T> cacheMap, BiFunction<Long, TransportMode, T> createDataWithId, boolean isDelete) {
        PacketCallback packetCallback = (updatePacket, fullPacket) -> KSDClientData.DATA_CACHE.sync();
        if (isDelete) {
            deleteData(dataSet, cacheMap, minecraftClient, packet, packetCallback, null);
        } else {
            updateData(dataSet, cacheMap, minecraftClient, packet, packetCallback, createDataWithId, null);
        }
    }

    public static void sendUpdate(ResourceLocation packetId, FriendlyByteBuf packet) {
        RegistryClient.sendToServer(packetId, packet);
        KSDClientData.DATA_CACHE.sync();
    }

    public static void sendDeleteData(ResourceLocation packetId, long id) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeLong(id);
        sendUpdate(packetId, packet);
    }

    public static void sendCreateSingleTicketC2S(int fare,
                                                 int amount,
                                                 boolean isConcessionary,
                                                 boolean firstClassAvailable,
                                                 BlockPos storeBlockPos) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeInt(fare);
        packet.writeInt(amount);
        packet.writeBoolean(isConcessionary);
        packet.writeBoolean(firstClassAvailable);
        packet.writeBlockPos(storeBlockPos);
        RegistryClient.sendToServer(KSD_PACKET_CREATE_SINGLE_TICKET, packet);
    }

    public static void sendAdjustSingleTicketFareC2S(ItemStack singleTicketItem,
                                                     int addValue,
                                                     BlockPos storeBlockPos) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeItem(singleTicketItem);
        packet.writeInt(addValue);
        packet.writeBlockPos(storeBlockPos);
        RegistryClient.sendToServer(KSD_PACKET_ADJUST_SINGLE_TICKET_FARE, packet);
    }

    public static void sendPaymentC2S(PaymentMethod paymentMethod,
                                      int needToPay,
                                      int actualPayment,
                                      BlockPos storeBlockPos) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeUtf(paymentMethod.name());
        packet.writeInt(needToPay);
        packet.writeInt(actualPayment);
        packet.writeBlockPos(storeBlockPos);
        RegistryClient.sendToServer(KSD_PACKET_PAYMENT, packet);
    }
}
