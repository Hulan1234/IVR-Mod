package net.hulan.ksd.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.data.EnumHelper;
import mtr.data.NameColorDataBase;
import mtr.data.TransportMode;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataBase;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.SingleTicketSystem;
import net.hulan.ksd.data.Octopus;
import net.hulan.ksd.data.PaymentMethod;
import net.hulan.ksd.sreen.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    public static void openTicketsScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        BlockPos storeBlockPos = packet.readBlockPos();
        int balance = packet.readInt();
        minecraftClient.execute(() -> {
            Player player = minecraftClient.player;
            if (player != null && !(minecraftClient.screen instanceof TicketsScreen)) {
                UtilitiesClient.setScreen(minecraftClient, new TicketsScreen(storeBlockPos, balance));
            }
        });
    }

    public static void openKCRSTMachineScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        SingleTicketSystem.TicketType ticketType = EnumHelper.valueOf(SingleTicketSystem.TicketType.MTR, packet.readUtf());
        BlockPos storeBlockPos = packet.readBlockPos();
        int balance = packet.readInt();
        minecraftClient.execute(() -> TicketsScreen.openSTMScreen(ticketType, storeBlockPos, balance));
    }

    public static void openKCRSTFareAdjustmentScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        int balance = packet.readInt();
        minecraftClient.execute(() -> TicketsScreen.openSTFAScreen(balance));
    }

    public static void openApplyOctopusScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        int balance = packet.readInt();
        minecraftClient.execute(() -> TicketsScreen.openApplyOctopusScreen(balance));
    }

    public static void openAddValueMachineScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
        BlockPos storeBlockPos = packet.readBlockPos();
        int balance = packet.readInt();
        minecraftClient.execute(() -> TicketsScreen.openAddValueScreen(storeBlockPos, balance));
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

    public static void sendCreateSTC2S(int fare,
                                       int amount,
                                       SingleTicketSystem.TicketType ticketType,
                                       boolean isConcessionary,
                                       boolean firstClassAvailable,
                                       BlockPos storeBlockPos) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeInt(fare);
        packet.writeInt(amount);
        packet.writeUtf(ticketType.name());
        packet.writeBoolean(isConcessionary);
        packet.writeBoolean(firstClassAvailable);
        packet.writeBlockPos(storeBlockPos);
        RegistryClient.sendToServer(KSD_PACKET_CREATE_SINGLE_TICKET, packet);
    }

    public static void sendAdjustSTFareC2S(long id,
                                           int addValue,
                                           BlockPos storeBlockPos) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeLong(id);
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

    public static void sendApplyOctopusC2S(int addValue,
                                            boolean isConcessionary,
                                            int amount) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeInt(addValue);
        packet.writeBoolean(isConcessionary);
        packet.writeInt(amount);
        RegistryClient.sendToServer(KSD_PACKET_CREATE_OCTOPUS, packet);
    }

    public static void sendOctopusAddValueC2S(UUID uuid,
                                              int addValue,
                                              Octopus.History.Source source) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeUUID(uuid);
        packet.writeInt(addValue);
        packet.writeUtf(source.name());
        RegistryClient.sendToServer(KSD_PACKET_OCTOPUS_ADD_VALUE, packet);
    }
}
