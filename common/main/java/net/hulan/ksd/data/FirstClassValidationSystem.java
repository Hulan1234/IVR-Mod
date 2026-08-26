package net.hulan.ksd.data;

import mtr.SoundEvents;
import mtr.data.*;
import mtr.mappings.Text;
import net.hulan.ksd.item.ItemOctopus;
import net.hulan.ksd.item.ItemSingleTicket;
import net.hulan.ksd.mixin.SidingAccessor;
import net.hulan.ksd.mixin.TrainInvoker;
import net.hulan.ksd.mixin.TrainServerAccessor;
import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.utils.Utilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class FirstClassValidationSystem {

    private static final int FC_EVASION_FINE = 1000;
    private static final String PLAYER_CAR_OBJECTIVE = "player_car";

    public static void tick(KSDRailwayData ksd, RailwayData mtr, Level world, List<ServerPlayer> players) {
        addObjectivesIfMissing(world);
        Set<Train> trains = getAllTrains(mtr);
        TrainServer playerTrain = null;
        int newCar = -1;
        for (ServerPlayer player : players) {
            Score carScore = getPlayerScore(world, player, PLAYER_CAR_OBJECTIVE);
            for (Train t : trains) {
                if (t.isPlayerRiding(player)) {
                    playerTrain = (TrainServer) t;
                    newCar = getCarForPlayer(player, playerTrain);
                    break;
                }
            }
            if (playerTrain == null) {
                carScore.setScore(0);
            } else {
                int oldCar = carScore.getScore() - 1;
                if (oldCar == newCar) continue;
                carScore.setScore(newCar + 1);
                if (oldCar == -1) continue;
                long routeId = ((TrainServerAccessor) playerTrain).getRouteId();
                KSDRoute route = DataUtilities.getRoute(ksd.routes, routeId);
                if (hasFCService(route) && newCar == route.firstClassCar) {
                    ItemStack holdingItem = player.getMainHandItem();
                    if (holdingItem.getItem() instanceof ItemSingleTicket) {
                        ticketValidate(world, player, holdingItem);
                        continue;
                    } else if (holdingItem.getItem() instanceof ItemOctopus) {
                        continue;
                    } else {
                        KSDStation firstStationInRoute = ksd.dataCache.platformIdToStation.get(route.getFirstPlatformId());
                        if (route.recommendedInterchangeStationId == 0) {
                            if (firstStationInRoute != null) {
                                route.recommendedInterchangeStationId = firstStationInRoute.id;
                            }
                        }
                        KSDStation firstStation = DataUtilities.getStation(ksd.stations, route.recommendedInterchangeStationId);
                        validate(ksd, world, player, player.blockPosition(), firstStation, route);
                    }
                }
            }
        }
    }

    public static void illegallyEntered(Level world, FirstClassPlayer firstClassPlayer, int percentageOffset) {
        addObjectivesIfMissing(world);
        Player levelPlayer = world.getPlayerByUUID(firstClassPlayer.uuid);
        if (levelPlayer != null) {
            firstClassPlayer.state = FirstClassState.ILLEGALLY;
            Score carScore = getPlayerScore(world, levelPlayer, PLAYER_CAR_OBJECTIVE);
            carScore.setScore(percentageOffset + 1);
            playSoundAndSendMessage(world, levelPlayer.blockPosition(), levelPlayer, "gui.ksd.first_class_illegal");
        }
    }

    public static FirstClassState ticketValidate(Level world, Player player, ItemStack ticketItem) {
        CompoundTag ticketTag = ticketItem.getOrCreateTag();
        long enteredStationId = ticketTag.getLong("entered_station_id");
        if (enteredStationId == 0L) {
            playSoundAndSendMessage(
                    world,
                    player.blockPosition(),
                    player,
                    "gui.ksd.fc_denied_no_entry");
            return FirstClassState.DENIED;
        }
        boolean isConcessionary = ticketTag.getBoolean("is_concessionary");
        boolean fcAvailable = ticketTag.getBoolean("fc_available");
        if (fcAvailable) {
            if (player.addTag("fc_validated")) {
                playSoundAndSendMessage(
                        world,
                        player.blockPosition(),
                        player,
                        isConcessionary ? "gui.ksd.fc_validated_concessionary" : "gui.ksd.fc_validated");
                return isConcessionary ? FirstClassState.VALIDATED_CONCESSIONARY : FirstClassState.VALIDATED;
            } else {
                playSoundAndSendMessage(
                        world,
                        player.blockPosition(),
                        player,
                        "gui.ksd.fc_already_validated");
                return FirstClassState.DENIED;
            }
        } else {
            playSoundAndSendMessage(
                    world,
                    player.blockPosition(),
                    player,
                    "gui.ksd.fc_denied_fc_unavailable");
            return FirstClassState.DENIED;
        }
    }

    public static void ticketDevalidate(Player player) {
        player.removeTag("fc_validated");
    }

    public static FirstClassState validateOnMachine(BlockPos clickedPos, Level world, Player player) {
        KSDRailwayData ksd = KSDRailwayData.getInstance(world);
        if (ksd == null) {
            playSoundAndSendMessage(world, clickedPos, player, "gui.ksd.first_class_denied");
            return FirstClassState.DENIED;
        }
        KSDStation station = KSDRailwayData.getStation(ksd.stations, clickedPos);
        if (station == null) {
            playSoundAndSendMessage(world, clickedPos, player, "gui.ksd.first_class_denied");
            return FirstClassState.DENIED;
        }
        for (KSDRoute route : ksd.dataCache.stationIdToRoutes.get(station.id)) {
            if (hasFCService(route)) {
                return validate(ksd, world, player, clickedPos, station, route);
            }
        }
        playSoundAndSendMessage(world, clickedPos, player, "gui.ksd.first_class_denied_no_matched_route");
        return FirstClassState.DENIED;
    }

    private static FirstClassState validate(KSDRailwayData ksd, Level world, Player player, BlockPos pos, KSDStation validateStation, KSDRoute route) {
        Score balance = getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE);
        Score entryZone = getPlayerScore(world, player, "mtr_entry_zone");
        FirstClassPlayer firstClassPlayer = ksd.jsonDataManager.getFirstClassPlayer(player.getUUID());
        if (entryZone.getScore() == 0 || firstClassPlayer == null) {
            return FirstClassState.DENIED;
        }
        if (!isValidated(firstClassPlayer)) {
            if (balance.getScore() < 0) {
                firstClassPlayer.state = FirstClassState.DENIED;
                playSoundAndSendMessage(world, pos, player, "gui.ksd.first_class_denied");
            } else {
                if (isConcessionary(player)) {
                    firstClassPlayer.state = FirstClassState.VALIDATED_CONCESSIONARY;
                    playSoundAndSendMessage(world, pos, player, "gui.ksd.first_class_enabled_access_concessionary");
                } else {
                    firstClassPlayer.state = FirstClassState.VALIDATED;
                    playSoundAndSendMessage(world, pos, player, "gui.ksd.first_class_enabled_access");
                }
            }
            ksd.dataCache.sync();
            return firstClassPlayer.state;
        } else {
            playSoundAndSendMessage(world, pos, player, "gui.ksd.first_class_already_valid");
            return FirstClassState.DENIED;
        }
    }

    public static boolean isValidated(FirstClassPlayer firstClassPlayer) {
        return firstClassPlayer.state.equals(FirstClassState.VALIDATED)
                || firstClassPlayer.state.equals(FirstClassState.VALIDATED_CONCESSIONARY);
    }

    private static Score getPlayerScore(Level world, Player player, String objectiveName) {
        return world.getScoreboard().getOrCreatePlayerScore(player.getGameProfile().getName(), world.getScoreboard().getObjective(objectiveName));
    }

    private static void addObjectivesIfMissing(Level world) {
        try {
            world.getScoreboard().addObjective(PLAYER_CAR_OBJECTIVE, ObjectiveCriteria.DUMMY, Text.literal("Player Car"), ObjectiveCriteria.RenderType.INTEGER);
        } catch (Exception ignored) {
        }
    }

    private static boolean isConcessionary(Player player) {
        return player.isCreative();
    }

    private static boolean hasFCService(@Nullable KSDRoute route) {
        return route != null && route.routeType.equals(Utilities.KCR_CLASSICAL) && route.hasFirstClassService;
    }

    private static void playSoundAndSendMessage(Level world, BlockPos pos, Player player, String message) {
        world.playSound(null, pos, SoundEvents.TICKET_BARRIER, SoundSource.PLAYERS, 1, 1);
        player.displayClientMessage(Text.translatable(message), false);
    }

    private static Set<Train> getAllTrains(@NotNull RailwayData railwayData) {
        Set<Train> allTrains = new HashSet<>();
        Set<Siding> sidings = railwayData.sidings;
        for (Siding siding : sidings) {
            Set<TrainServer> trains = ((SidingAccessor) siding).getTrains();
            for (TrainServer train : trains) {
                if (train.isOnRoute()) {
                    allTrains.add(train);
                }
            }
        }
        return allTrains;
    }

    private static int getCarForPlayer(Player player, Train train) {
        for (int i = 0; i < train.trainCars; i++) {
            if (isPlayerInCar(player, train, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPlayerInCar(Player player, Train train, int carriageIndex) {
        Vec3[] points = getCarConnectionPoints(train, carriageIndex);
        Vec3 p1 = points[0];
        Vec3 p2 = points[1];
        if (p1 == null || p2 == null) return false;
        Vec3 center = new Vec3((p1.x + p2.x) / 2, (p1.y + p2.y) / 2 + 1, (p1.z + p2.z) / 2);
        double dx = p2.x - p1.x;
        double dz = p2.z - p1.z;
        double dy = p2.y - p1.y;
        float yaw = (float) Mth.atan2(dx, dz);
        float pitch = (float) Math.asin(dy / p1.distanceTo(p2));
        Vec3 delta = player.position().subtract(center);
        Vec3 local = delta.yRot(-yaw).xRot(-pitch);
        double halfLength = p1.distanceTo(p2) / 2;
        double halfWidth = train.width / 2.0;
        return Math.abs(local.x) < halfWidth + 1.0F &&
                Math.abs(local.z) < halfLength + 1.0F &&
                local.y >= -0.5 && local.y <= 3.0;
    }

    private static Vec3[] getCarConnectionPoints(Train train, int carriageIndex) {
        TrainInvoker accessor = (TrainInvoker) train;
        int spacing = train.spacing;
        int trainCars = train.trainCars;
        boolean reversed = train.isReversed();
        Vec3[] positions = new Vec3[trainCars + 1];
        for (int i = 0; i <= trainCars; i++) {
            int idx = reversed ? trainCars - i : i;
            positions[i] = accessor.invokeGetRoutePosition(idx, spacing);
        }
        return new Vec3[]{positions[carriageIndex], positions[carriageIndex + 1]};
    }

    public enum FirstClassState implements StringRepresentable {

        MTR("mtr"),
        ILLEGALLY("illegally"),
        VALIDATED("validated"),
        VALIDATED_CONCESSIONARY("validated_concessionary"),
        DENIED("denied");

        private final String name;

        FirstClassState(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public @NotNull String getSerializedName() {
            return name;
        }
    }
}
