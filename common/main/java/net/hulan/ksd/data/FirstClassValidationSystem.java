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
import net.hulan.ksd.utils.RailDataUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.NotNull;

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
                if (RailDataUtilities.hasFirstClassService(route) && newCar == route.firstClassCar) {
                    ItemStack holdingItem = player.getMainHandItem();
                    if (holdingItem.getItem() instanceof ItemSingleTicket || holdingItem.getItem() instanceof ItemOctopus) {
                        validate(world, ksd, player, holdingItem, holdingItem.getItem() instanceof ItemOctopus);
                    } else {
                        illegallyEntered(world, player, newCar);
                    }
                }
            }
        }
    }

    public static void illegallyEntered(Level world, Player player, int percentageOffset) {
        addObjectivesIfMissing(world);
        Score carScore = getPlayerScore(world, player, PLAYER_CAR_OBJECTIVE);
        Score balance = getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE);
        carScore.setScore(percentageOffset + 1);
        balance.setScore(balance.getScore() - FC_EVASION_FINE);
        playSoundAndSendMessage(world, player.blockPosition(), player, "gui.ksd.first_class_illegal");
    }

    public static FirstClassState validate(Level world, KSDRailwayData railwayData, Player player, ItemStack item, boolean isOctopus) {
        CompoundTag ticketTag = item.getOrCreateTag();
        if (!KCRTicketSystem.isEntered(ticketTag, railwayData.stations, isOctopus)) {
            playSoundAndSendMessage(
                    world,
                    player.blockPosition(),
                    player,
                    "gui.ksd.fc_denied_no_entry");
            return FirstClassState.DENIED;
        }
        boolean isConcessionary = ticketTag.getBoolean("is_concessionary");
        if (!isOctopus && !ticketTag.getBoolean("fc_available")) {
            playSoundAndSendMessage(
                    world,
                    player.blockPosition(),
                    player,
                    "gui.ksd.fc_denied_fc_unavailable");
            return FirstClassState.DENIED;
        }
        if (validate(player)) {
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
    }

    public static boolean validate(Player player) {
        return player.addTag("fc_validated");
    }

    public static boolean isValidated(Player player) {
        return player.getTags().contains("fc_validated");
    }

    public static void devalidate(Player player) {
        player.removeTag("fc_validated");
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

    public enum FirstClassState {

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
    }
}
