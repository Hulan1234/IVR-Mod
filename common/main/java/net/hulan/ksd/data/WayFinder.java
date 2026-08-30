package net.hulan.ksd.data;

import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.utils.RailDataUtilities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class WayFinder {

    public final Set<KSDStation> stations;
    public final Map<Long, KSDStation> platformIdToStation;
    public final Map<Long, Set<KSDPlatform>> stationIdToPlatforms;
    public final Map<Long, List<KSDStation>> routeIdToStationsWithIndex;
    public final Map<Long, Set<KSDRoute>> stationIdToRoutes;
    public final Map<KSDStation, Set<KSDStation>> stationIdToConnectingStations;
    public final Map<Long, Set<KSDRoute>> stationIdToNetwork = new HashMap<>();
    public final Map<Long, Set<StationContext>> stationIdToStationContexts = new HashMap<>();

    public WayFinder(KSDDataCache dataCache) {
        stations = dataCache.stations;
        platformIdToStation = dataCache.platformIdToStation;
        stationIdToPlatforms = dataCache.stationIdToPlatforms;
        routeIdToStationsWithIndex = dataCache.routeIdToStationsWithIndex;
        stationIdToRoutes = dataCache.stationIdToRoutes;
        stationIdToConnectingStations = dataCache.stationIdToConnectingStations;
    }

    public void sync() {
        stationIdToNetwork.clear();
        stationIdToStationContexts.clear();
        stations.forEach(s -> stationIdToNetwork.put(s.id, generateNetwork(s)));
        stations.forEach(s -> stationIdToStationContexts.put(s.id, generateStationContexts(s)));
    }

    public Set<KSDRoute> getNetwork(long stationId) {
        return stationIdToNetwork.getOrDefault(stationId, Set.of());
    }

    /**
     *
     *
     *
     *
     */
    private Set<KSDRoute> generateNetwork(KSDStation current) {
        Set<KSDRoute> network = new HashSet<>();
        Queue<KSDRoute> queue = new ArrayDeque<>();
        Set<KSDRoute> routesInCurrent = stationIdToRoutes.getOrDefault(current.id, Set.of());
        for (KSDRoute routeInCurrent : routesInCurrent) {
            if (network.add(routeInCurrent)) {
                queue.add(routeInCurrent);
            }
        }
        while (!queue.isEmpty()) {
            KSDRoute route = queue.poll();
            List<KSDStation> stationsInRoute = routeIdToStationsWithIndex.getOrDefault(route.id, List.of());
            for (KSDStation stationInRoute : stationsInRoute) {
                Set<KSDRoute> routesInStation = stationIdToRoutes.getOrDefault(stationInRoute.id, Set.of());
                for (KSDRoute routeInStation : routesInStation) {
                    if (network.add(routeInStation)) {
                        queue.add(routeInStation);
                    }
                }
            }
        }
        return network;
    }

    public List<RouteSegment> findBestRoute(KSDStation start, KSDStation destination) {
        Map<StationContext, PathCost> bestCost = new HashMap<>();
        Map<StationContext, StationContext> previous = new HashMap<>();
        Set<StationContext> discovered = new HashSet<>();
        PriorityQueue<StationContext> priorityQueue = new PriorityQueue<>((a, b) ->
                bestCost.getOrDefault(a, PathCost.MAX).compare(bestCost.getOrDefault(b, PathCost.MAX)));
        for (StationContext startContext : stationIdToStationContexts.getOrDefault(start.id, Set.of())) {
            if (discovered.add(startContext)) {
                priorityQueue.offer(startContext);
                bestCost.put(startContext, PathCost.ZERO);
            }
        }
        while (!priorityQueue.isEmpty()) {
            StationContext currentContext = priorityQueue.poll();
            if (destination.equals(currentContext.current())) { // 如果当前状态已经到达目标站
                List<StationContext> statePath = reconstructContextPath(currentContext, previous); // 从终点向前恢复完整状态路径
                return buildRouteSegments(statePath); // 将状态路径合并成最终的 RouteSegment 列表
            }
            StationContext previousContext = findPreviousStationContext(currentContext);
            StationContext nextContext = findNextStationContext(currentContext);
            if (previousContext != null) {
                updateNext(currentContext, previousContext, priorityQueue, bestCost, previous, discovered);
            }
            if (nextContext != null) {
                updateNext(currentContext, nextContext, priorityQueue, bestCost, previous, discovered);
            }
            for (StationContext otherContext : stationIdToStationContexts.getOrDefault(currentContext.current().id, Set.of())) {
                if (RailDataUtilities.isSameRoute(currentContext.route(), otherContext.route())) {
                    continue;
                }
                updateTransfer(currentContext, otherContext, priorityQueue, bestCost, previous, discovered);
            }
        }
        return List.of();
    }

    private Set<StationContext> generateStationContexts(KSDStation current) {
        Set<StationContext> routeSections = new HashSet<>();
        Set<KSDRoute> routesInStation = stationIdToRoutes.getOrDefault(current.id, Set.of());
        routesInStation.forEach(r -> {
            List<KSDStation> stationsAndIndex = routeIdToStationsWithIndex.getOrDefault(r.id, List.of());
            List<Integer> indexes = DataUtilities.getAllIndexFromList(stationsAndIndex, current);
            for (int index : indexes) {
                KSDStation previous = index - 1 >= 0 ? stationsAndIndex.get(index - 1) : null;
                KSDStation next = index + 1 < stationsAndIndex.size() ? stationsAndIndex.get(index + 1) : null;
                routeSections.add(new StationContext(current, previous, next, r));
            }
        });
        return routeSections;
    }

    private StationContext findNextStationContext(StationContext currentContext) {
        KSDStation next = currentContext.next();
        if (next != null) {
            Set<StationContext> otherContexts = stationIdToStationContexts.getOrDefault(next.id, Set.of());
            for (StationContext otherContext : otherContexts) {
                if (RailDataUtilities.isSameRoute(currentContext.route(), otherContext.route())
                        && RailDataUtilities.isSameStation(currentContext.current(), otherContext.previous())) {
                    return otherContext;
                }
            }
        }
        return null;
    }


    private StationContext findPreviousStationContext(StationContext currentContext) {
        KSDStation previous = currentContext.previous();
        if (previous != null) {
            Set<StationContext> otherContexts = stationIdToStationContexts.getOrDefault(previous.id, Set.of());
            for (StationContext otherContext : otherContexts) {
                if (RailDataUtilities.isSameRoute(currentContext.route(), otherContext.route())
                        && RailDataUtilities.isSameStation(currentContext.current(), otherContext.next())) {
                    return otherContext;
                }
            }
        }
        return null;
    }

    /**
     * 更新同一线路状态
     * 1.计算新的成本C1（有头等）
     * 2.获取下一站的成本C2
     * 3.如果C1<C2，则保存
     *
     *
     */
    private void updateNext(StationContext currentContext,
                            StationContext nextContext,
                            PriorityQueue<StationContext> priorityQueue,
                            Map<StationContext, PathCost> bestCost,
                            Map<StationContext, StationContext> previous,
                            Set<StationContext> visited) {
        PathCost currentCopy = bestCost.get(currentContext).copy().addRide(RailDataUtilities.hasFirstClassService(currentContext.route()));
        PathCost nextCopy = bestCost.getOrDefault(nextContext, PathCost.MAX).copy();
        if (currentCopy.better(nextCopy)) {
            bestCost.put(nextContext, nextCopy.setCosts(currentCopy));
            previous.put(nextContext, currentContext);
            if (visited.add(nextContext)) {
                priorityQueue.offer(nextContext);
            } else {
                DataUtilities.sortPriorityQueue(priorityQueue);
            }
        }
    }

    private void updateTransfer(StationContext currentContext,
                               StationContext transferContext,
                               PriorityQueue<StationContext> priorityQueue,
                               Map<StationContext, PathCost> bestCost,
                               Map<StationContext, StationContext> previous,
                               Set<StationContext> visited) {
        PathCost currentCopy = bestCost.get(currentContext).copy().addTransfer();
        PathCost transferCopy = bestCost.getOrDefault(transferContext, PathCost.MAX).copy();
        if (currentCopy.better(transferCopy)) {
            bestCost.put(transferContext, transferCopy.setCosts(currentCopy));
            previous.put(transferContext, currentContext);
            if (visited.add(transferContext)) {
                priorityQueue.offer(transferContext);
            } else {
                DataUtilities.sortPriorityQueue(priorityQueue);
            }
        }
    }

    private List<StationContext> reconstructContextPath(StationContext targetContext, Map<StationContext, StationContext> previous) {
        List<StationContext> result = new ArrayList<>();
        StationContext currentContext = targetContext;
        while (currentContext != null) {
            result.add(currentContext);
            currentContext = previous.get(currentContext);
        }
        //反转结果
        Collections.reverse(result);
        return result;
    }

    private List<RouteSegment> buildRouteSegments(List<StationContext> statePath) {
        if (statePath.size() < 2) {
            return List.of();
        }
        List<RouteSegment> result = new ArrayList<>();
        KSDStation segmentFrom = statePath.get(0).current(); // 当前 RouteSegment 的起点站
        KSDRoute segmentRoute = statePath.get(0).route(); // 当前 RouteSegment 使用的线路
        for (int i = 1; i < statePath.size(); i++) { // 从第二个状态开始遍历
            StationContext previousContext = statePath.get(i - 1); // 获取前一个状态
            StationContext currentContext = statePath.get(i); // 获取当前状态
            boolean sameRoute = RailDataUtilities.isSameRoute(previousContext.route(), currentContext.route()); // 判断前后两个状态是否仍然属于同一业务线路
            if (!sameRoute) { // 如果线路发生变化，说明这里发生了换乘
                result.add(new RouteSegment(
                                segmentFrom, // 当前线路段的起始站
                                previousContext.current(), // 当前线路段的结束站
                                segmentRoute, // 当前线路段使用的线路
                                RailDataUtilities.hasFirstClassService(segmentRoute)));
                segmentFrom = currentContext.current(); // 新线路段从换乘站开始
                segmentRoute = currentContext.route(); // 新线路段切换到新的线路
            }
        }
        StationContext lastContext = statePath.get(statePath.size() - 1); // 获取最终状态
        result.add(new RouteSegment(segmentFrom, // 最后一个线路段的起始站
                                    lastContext.current(), // 最后一个线路段的终点站
                                    segmentRoute, // 最后一个线路段使用的线路
                                    RailDataUtilities.hasFirstClassService(segmentRoute))); // 将最后一个线路段加入结果
        return result; // 返回最终的 RouteSegment 列表
    }

    public record StationContext(@NotNull KSDStation current,
                                 @Nullable KSDStation previous,
                                 @Nullable KSDStation next,
                                 @NotNull KSDRoute route) {

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof StationContext state) {
                return RailDataUtilities.isSameStation(current, state.current)
                        && RailDataUtilities.isSameStation(previous, state.previous)
                        && RailDataUtilities.isSameStation(next, state.next)
                        && RailDataUtilities.isSameRoute(route, state.route);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    RailDataUtilities.stationHashCode(current),
                    RailDataUtilities.stationHashCode(previous),
                    RailDataUtilities.stationHashCode(next),
                    RailDataUtilities.routeHashCode(route));
        }
    }

    public static final class PathCost {

        public static final PathCost MAX = new PathCost(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        public static final PathCost ZERO = new PathCost(0, 0, 0);

        private int fcCost;
        private int rideCost;
        private int transferCost;

        public PathCost(int fcCost,
                        int rideCost,
                        int transferCost) {
            this.fcCost = fcCost;
            this.rideCost = rideCost;
            this.transferCost = transferCost;
        }

        public boolean better(@NotNull WayFinder.PathCost other) {
            if (fcCost != other.fcCost) {
                return fcCost < other.fcCost;
            }
            if (transferCost != other.transferCost) {
                return transferCost < other.transferCost;
            }
            return rideCost < other.rideCost;
        }

        public int compare(PathCost other) {
            if (fcCost != other.fcCost) {
                return Integer.compare(fcCost, other.fcCost);
            }
            if (transferCost != other.transferCost) {
                return Integer.compare(transferCost, other.transferCost);
            }
            return Integer.compare(rideCost, other.rideCost);
        }

        public PathCost copy() {
            return new PathCost(fcCost, rideCost, transferCost);
        }

        public PathCost addRide(boolean hasFCService) {
            fcCost += (hasFCService ? 1 : 0);
            rideCost += 1;
            return this;
        }

        public PathCost addTransfer() {
            transferCost += 1;
            return this;
        }

        public PathCost setCosts(PathCost other) {
            fcCost = other.fcCost;
            rideCost = other.rideCost;
            transferCost = other.transferCost;
            return this;
        }
    }

    public record RouteSegment(@NotNull KSDStation from,
                               @NotNull KSDStation to,
                               KSDRoute route,
                               boolean hasFCService) {
    }
}
