package net.hulan.ksd.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.data.*;
import mtr.mappings.Utilities;
import mtr.packet.PacketTrainDataBase;
import net.hulan.ivr.block.StorableBlockEntity;
import net.hulan.ksd.data.*;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.sreen.KCRSingleTicketMachineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Score;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

public class KSDPacketServer extends PacketTrainDataBase implements KSDPacket {
    
    private static final int PACKET_CHUNK_SIZE = (int) Math.pow(2.0F, 14.0F);

    public static void openKSDDashboardScreenS2C(ServerPlayer player, TransportMode transportMode, boolean useTimeAndWindSync) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeUtf(transportMode.toString());
        packet.writeBoolean(useTimeAndWindSync);
        Registry.sendToPlayer(player, KSD_PACKET_OPEN_KSD_DASHBOARD_SCREEN, packet);
    }

    public static void openKCRTicketMachineScreenS2C(ServerPlayer player, KCRSingleTicketMachineScreen.RailMapType railMapType, BlockPos pos) {
        int balance = TicketSystem.getPlayerScore(player.getLevel(), player, "mtr_balance").getScore();
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeUtf(railMapType.name());
        packet.writeBlockPos(pos);
        packet.writeInt(balance);
        Registry.sendToPlayer(player, KSD_PACKET_OPEN_KCR_SINGLE_TICKET_MACHINE_SCREEN, packet);
    }

    public static void openSingleTicketAdjustmentScreenS2C(ServerPlayer player, BlockPos pos) {
        int balance = TicketSystem.getPlayerScore(player.getLevel(), player, "mtr_balance").getScore();
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeBlockPos(pos);
        packet.writeInt(balance);
        Registry.sendToPlayer(player, KSD_PACKET_OPEN_KCR_SINGLE_TICKET_FARE_ADJUSTMENT_SCREEN, packet);
    }

    public static void sendAllInChunks(ServerPlayer player, Set<KSDStation> stations, Set<KSDPlatform> platforms, Set<KSDRoute> routes) {
        final long tempPacketId = new Random().nextLong();
        final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        serializeData(packet, stations);
        serializeData(packet, platforms);
        serializeData(packet, routes);
        int i = 0;
        while (!sendChunk(player, packet, tempPacketId, i)) {
            i++;
        }
    }

    private static boolean sendChunk(ServerPlayer player, FriendlyByteBuf packet, long tempPacketId, int chunk) {
        FriendlyByteBuf packetChunk = new FriendlyByteBuf(Unpooled.buffer());
        packetChunk.writeLong(tempPacketId);
        packetChunk.writeInt(chunk);
        boolean success = chunk * PACKET_CHUNK_SIZE > packet.readableBytes();
        packetChunk.writeBoolean(success);
        if (!success) {
            packetChunk.writeBytes(packet.copy(chunk * PACKET_CHUNK_SIZE, Math.min(PACKET_CHUNK_SIZE, packet.readableBytes() - chunk * PACKET_CHUNK_SIZE)));
        }
        try {
            Registry.sendToPlayer(player, KSD_PACKET_CHUNK_S2C, packetChunk);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return success;
    }

    public static <T extends NameColorDataBase> void receiveUpdateOrDeleteC2S(MinecraftServer minecraftServer,
                                                                              ServerPlayer player,
                                                                              FriendlyByteBuf packet,
                                                                              ResourceLocation packetId,
                                                                              Function<KSDRailwayData, Set<T>> dataSet,
                                                                              Function<KSDRailwayData, Map<Long, T>> cacheMap,
                                                                              BiFunction<Long, TransportMode, T> createDataWithId,
                                                                              boolean isDelete) {
        if (!KSDRailwayData.hasNoPermission(player)) {
            Level world = player.level;
            KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
            if (railwayData != null) {
                PacketCallback packetCallback = (updatePacket, fullPacket) -> world.players().forEach((worldPlayer) -> {
                    if (!worldPlayer.getUUID().equals(player.getUUID())) {
                        Registry.sendToPlayer((ServerPlayer)worldPlayer, packetId, fullPacket);
                    }
                    railwayData.dataCache.sync();
                });
                if (isDelete) {
                    deleteData(
                            dataSet.apply(railwayData),
                            cacheMap.apply(railwayData),
                            minecraftServer,
                            packet,
                            packetCallback,
                            (data) -> railwayData.railwayDataLoggingModule.addEvent(player, data.getClass(), data.id, data.name, KSDRailwayDataLoggingModule.getData(data), new ArrayList<>()));
                } else {
                    updateData(
                            dataSet.apply(railwayData),
                            cacheMap.apply(railwayData),
                            minecraftServer,
                            packet,
                            packetCallback,
                            createDataWithId,
                            (data, oldData) -> railwayData.railwayDataLoggingModule.addEvent(player, data.getClass(), data.id, data.name, oldData, KSDRailwayDataLoggingModule.getData(data)));
                }
            }
        }
    }

    public static void receiveCreateSingleTicketC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
        int fare = packet.readInt();
        int amount = packet.readInt();
        boolean isConcessionary = packet.readBoolean();
        boolean fcAvailable = packet.readBoolean();
        BlockPos storeBlockPos = packet.readBlockPos();
        minecraftServer.execute(() -> {
            Level world = player.level;
            List<ItemStack> items = new ArrayList<>(amount);
            for (int i = 1; i <= amount; i++) {
                items.add(KCRSingleTicketSystem.createSingleTicketItem(fare, isConcessionary, fcAvailable));
            }
            if (world.getBlockEntity(storeBlockPos) instanceof StorableBlockEntity entity) {
                entity.storeItems(items);
            } else {
                items.forEach(item -> Utilities.getInventory(player).add(item));
            }
        });
    }

    public static void receiveAdjustSingleTicketFareC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
        ItemStack identifyItem = packet.readItem();
        int addValue = packet.readInt();
        BlockPos storeBlockPos = packet.readBlockPos();
        minecraftServer.execute(() -> {
            Level world = player.level;
            List<ItemStack> items = new ArrayList<>();
            Inventory inventory = Utilities.getInventory(player);
            ItemStack singleTicketItem = KCRSingleTicketSystem.findSingleTicketItem(identifyItem, inventory);
            if (!singleTicketItem.isEmpty()) {
                inventory.removeItem(singleTicketItem);
                KCRSingleTicketSystem.adjustSingleTicketFare(singleTicketItem, addValue);
                items.add(singleTicketItem);
                if (world.getBlockEntity(storeBlockPos) instanceof StorableBlockEntity entity) {
                    entity.storeItems(items);
                } else {
                    items.forEach(inventory::add);
                }
            }
        });
    }

    public static void receivePayment(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
        PaymentMethod paymentMethod = EnumHelper.valueOf(PaymentMethod.MTR_BALANCE, packet.readUtf());
        int needToPay = packet.readInt();
        int actualPayment = packet.readInt();
        BlockPos storeBlockPos = packet.readBlockPos();
        minecraftServer.execute(() -> {
            Level world = player.level;
            Inventory inventory = Utilities.getInventory(player);
            switch (paymentMethod) {
                case EMERALDS -> {
                    List<ItemStack> items = new ArrayList<>();
                    int change = actualPayment - needToPay;
                    ContainerHelper.clearOrCountMatchingItems(inventory, (itemStack) -> itemStack.getItem() == Items.EMERALD, actualPayment, false);
                    if (change > 0) {
                        ItemStack emeraldsStack = new ItemStack(Items.EMERALD, change);
                        items.add(emeraldsStack);
                    }
                    if (world.getBlockEntity(storeBlockPos) instanceof StorableBlockEntity entity) {
                        entity.storeItems(items);
                    } else {
                        items.forEach(inventory::add);
                    }
                    world.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                case MTR_BALANCE -> {
                    TicketSystem.addObjectivesIfMissing(world);
                    Score balanceScore = TicketSystem.getPlayerScore(world, player, "mtr_balance");
                    balanceScore.setScore(balanceScore.getScore() - actualPayment);
                }
            }
        });
    }

    private static <T extends SerializedDataBase> void serializeData(FriendlyByteBuf packet, Collection<T> objects) {
        packet.writeInt(objects.size());
        objects.forEach((object) -> object.writePacket(packet));
    }
}
