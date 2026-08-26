package net.hulan.ksd.data;

import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.utils.RailDataUtilities;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class WayFinder {

    public final Map<Long, Set<KSDRoute>> stationIdToNetwork = new HashMap<>();
    public final Map<Long, Set<StationContext>> stationIdToStationContexts = new HashMap<>();

    public WayFinder() {
    }

    public void sync(KSDDataCache dataCache) {
        dataCache.stations.forEach(s -> stationIdToNetwork.put(s.id, getNetwork(s, dataCache)));
        dataCache.stations.forEach(s -> stationIdToStationContexts.put(s.id, getStationContexts(s, dataCache)));
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
    private Set<KSDRoute> getNetwork(KSDStation current, KSDDataCache dataCache) {
        Set<KSDRoute> network = new HashSet<>();
        Queue<KSDRoute> queue = new ArrayDeque<>();
        Set<KSDRoute> routesInCurrent = dataCache.stationIdToRoutes.getOrDefault(current.id, Set.of());
        for (KSDRoute routeInCurrent : routesInCurrent) {
            if (network.add(routeInCurrent)) {
                queue.add(routeInCurrent);
            }
        }
        while (!queue.isEmpty()) {
            KSDRoute route = queue.poll();
            List<KSDStation> stationsInRoute = dataCache.routeIdToStationsWithIndex.getOrDefault(route.id, List.of());
            for (KSDStation stationInRoute : stationsInRoute) {
                Set<KSDRoute> routesInStation = dataCache.stationIdToRoutes.getOrDefault(stationInRoute.id, Set.of());
                for (KSDRoute routeInStation : routesInStation) {
                    if (network.add(routeInStation)) {
                        queue.add(routeInStation);
                    }
                }
            }
        }
        return network;
    }

    public Set<StationContext> getStationContexts(KSDStation current, KSDDataCache dataCache) {
        Set<StationContext> routeSections = new HashSet<>();
        Set<KSDRoute> routesInStation = dataCache.stationIdToRoutes.getOrDefault(current.id, Set.of());
        routesInStation.forEach(r -> {
            List<KSDStation> stationsAndIndex = dataCache.routeIdToStationsWithIndex.getOrDefault(r.id, List.of());
            List<Integer> indexes = DataUtilities.getAllIndexFromList(stationsAndIndex, current);
            for (int index : indexes) {
                KSDStation previous = index - 1 >= 0 ? stationsAndIndex.get(index - 1) : null;
                KSDStation next = index + 1 < stationsAndIndex.size() ? stationsAndIndex.get(index + 1) : null;
                routeSections.add(new StationContext(current, previous, next, r));
            }
        });
        return routeSections;
    }

    public List<RouteSegment> findBestRoute(KSDStation start, KSDStation destination) {
        Map<StationState, Integer> distance = new HashMap<>(); // 保存到达每个状态所需的最少头等区间数
        Map<StationState, StationState> previous = new HashMap<>(); // 保存每个状态是从哪个状态走过来的
        Deque<StationState> deque = new ArrayDeque<>(); // 使用双端队列实现 0-1 BFS
        for (StationContext context : stationIdToStationContexts.getOrDefault(start.id, Set.of())) { // 遍历起点站所有可用的线路上下文
            if (context.route() == null) { // 如果线路为空
                continue; // 跳过这个无效上下文
            }
            StationState startState = new StationState(start, context.route()); // 创建起点对应的搜索状态
            if (distance.putIfAbsent(startState, 0) == null) { // 如果这个起点状态还没有加入搜索
                deque.addLast(startState); // 将起点状态放入队尾
            }
        }
        while (!deque.isEmpty()) { // 只要还有待处理状态就继续搜索
            StationState currentState = deque.pollFirst(); // 取出当前优先级最高的状态
            int currentCost = distance.get(currentState); // 获取到达当前状态所需的最少头等区间数
            if (RailDataUtilities.isSameStation(currentState.current(), destination)) { // 如果当前状态已经到达目标站
                List<StationState> statePath = reconstructStatePath(currentState, previous); // 从终点向前恢复完整状态路径
                return buildRouteSegments(statePath); // 将状态路径合并成最终的 RouteSegment 列表
            }
            StationContext currentContext = findStationContext(currentState); // 找到当前站在当前线路上的上下文
            if (currentContext == null) { // 如果没有找到对应的线路上下文
                continue; // 无法从当前状态继续扩展，直接跳过
            }
            boolean currentRouteHasFC = RailDataUtilities.hasFirstClassService(currentState.route()); // 判断当前乘坐的线路是否提供头等服务
            int rideCost = currentRouteHasFC ? 1 : 0; // 每沿当前线路前进一站，头等线路增加 1，普通线路增加 0
            if (currentContext.previous() != null) { // 如果当前线路存在上一站
                relaxRide(
                        currentState, // 传入当前状态
                        currentContext.previous(), // 目标是线路上的上一站
                        rideCost, // 使用当前线路前进一站对应的成本
                        deque, // 传入 0-1 BFS 队列
                        distance, // 传入距离表
                        previous // 传入前驱表
                ); // 尝试更新上一站状态
            }

            if (currentContext.next() != null) { // 如果当前线路存在下一站
                relaxRide(
                        currentState, // 传入当前状态
                        currentContext.next(), // 目标是线路上的下一站
                        rideCost, // 使用当前线路前进一站对应的成本
                        deque, // 传入 0-1 BFS 队列
                        distance, // 传入距离表
                        previous // 传入前驱表
                ); // 尝试更新下一站状态
            }

            Set<StationContext> stationContexts = stationIdToStationContexts.getOrDefault(currentState.current().id, Set.of()); // 获取当前站所有线路上下文
            for (StationContext otherContext : stationContexts) { // 遍历当前站可以使用的其他线路
                if (otherContext.route() == null) { // 如果其他线路为空
                    continue; // 跳过无效线路
                }
                if (RailDataUtilities.isSameRoute(currentState.route(), otherContext.route())) { // 如果新线路和当前线路属于同一业务线路
                    continue; // 不需要额外创建一个换乘状态
                }
                StationState nextState = new StationState(currentState.current(), otherContext.route()); // 创建“在当前站换乘到另一条线路”的新状态

                int newCost = currentCost; // 换乘本身不增加头等线路经过次数，所以成本保持不变

                int oldCost = distance.getOrDefault(nextState, Integer.MAX_VALUE); // 获取该状态之前记录的最优成本

                if (newCost < oldCost) { // 如果通过当前状态可以得到更优结果

                    distance.put(nextState, newCost); // 更新该状态的最小成本

                    previous.put(nextState, currentState); // 记录该状态是通过当前状态换乘得到的

                    deque.addFirst(nextState); // 换乘成本为 0，所以把状态放到队首
                }
            }
        }

        return List.of(); // 搜索完整个图仍然没有找到目标站，返回空路线
    }

    private StationContext findStationContext(StationState state) {
        Set<StationContext> contexts = stationIdToStationContexts.getOrDefault(state.current().id, Set.of());
        for (StationContext context : contexts) {
            // 当前 Context 必须确实属于当前 State 的车站
            if (!RailDataUtilities.isSameStation(state.current(), context.current())) {
                continue;
            }
            // 当前 Context 必须属于当前 State 的线路
            if (!RailDataUtilities.isSameRoute(state.route(), context.route())) {
                continue;
            }
            return context;
        }

        return null;
    }

    private void relaxRide(StationState currentState,
                           KSDStation nextStation,
                           int rideCost,
                           Deque<StationState> deque,
                           Map<StationState, Integer> distance,
                           Map<StationState, StationState> previous) {
        StationState nextState = new StationState(nextStation, currentState.route()); // 创建“继续乘坐当前线路到下一站”的状态

        int newCost = distance.get(currentState) + rideCost; // 计算到达下一状态后的头等区间累计数

        int oldCost = distance.getOrDefault(nextState, Integer.MAX_VALUE
        ); // 获取这个状态之前已知的最优成本

        if (newCost >= oldCost) { // 如果新路线没有更优
            return; // 不需要更新
        }

        distance.put(nextState, newCost); // 保存新的最优成本

        previous.put(nextState, currentState); // 保存前驱状态，用于最终恢复路线

        if (rideCost == 0) { // 如果当前线路是普通线路
            deque.addFirst(nextState); // 普通线路成本为 0，放到队首
        } else { // 如果当前线路是头等线路
            deque.addLast(nextState); // 头等线路成本为 1，放到队尾
        }
    }

    private List<StationState> reconstructStatePath(StationState targetState, Map<StationState, StationState> previous) {
        List<StationState> result = new ArrayList<>(); // 创建保存最终状态路径的列表

        StationState currentState = targetState; // 从终点状态开始反向追踪

        while (currentState != null) { // 只要还有前驱状态

            result.add(currentState); // 将当前状态加入结果

            currentState = previous.get(currentState); // 移动到当前状态的前驱
        }

        Collections.reverse(result); // 因为刚才是从终点向起点倒推，所以现在反转为正向路线

        return result; // 返回从起点到终点的状态路径
    }

    private List<RouteSegment> buildRouteSegments(
            List<StationState> statePath
    ) {
        if (statePath.size() < 2) { // 如果路径少于两个状态
            return List.of(); // 没有实际乘车区间，直接返回空列表
        }

        List<RouteSegment> result = new ArrayList<>(); // 创建最终的线路段列表

        KSDStation segmentFrom =
                statePath.get(0).current(); // 当前 RouteSegment 的起点站

        KSDRoute segmentRoute =
                statePath.get(0).route(); // 当前 RouteSegment 使用的线路

        for (int i = 1; i < statePath.size(); i++) { // 从第二个状态开始遍历

            StationState previousState =
                    statePath.get(i - 1); // 获取前一个状态

            StationState currentState =
                    statePath.get(i); // 获取当前状态

            boolean sameRoute =
                    RailDataUtilities.isSameRoute(
                            previousState.route(),
                            currentState.route()
                    ); // 判断前后两个状态是否仍然属于同一业务线路

            if (!sameRoute) { // 如果线路发生变化，说明这里发生了换乘

                result.add(
                        new RouteSegment(
                                segmentFrom, // 当前线路段的起始站
                                previousState.current(), // 当前线路段的结束站
                                segmentRoute, // 当前线路段使用的线路
                                RailDataUtilities.hasFirstClassService(
                                        segmentRoute
                                ) // 判断当前线路段是否提供头等服务
                        )
                ); // 保存刚刚结束的线路段

                segmentFrom =
                        currentState.current(); // 新线路段从换乘站开始

                segmentRoute =
                        currentState.route(); // 新线路段切换到新的线路
            }
        }

        StationState lastState =
                statePath.get(statePath.size() - 1); // 获取最终状态

        result.add(new RouteSegment(
                        segmentFrom, // 最后一个线路段的起始站
                        lastState.current(), // 最后一个线路段的终点站
                        segmentRoute, // 最后一个线路段使用的线路
                        RailDataUtilities.hasFirstClassService(
                                segmentRoute
                        ) // 判断最后一个线路段是否提供头等服务
                )
        ); // 将最后一个线路段加入结果

        return result; // 返回最终的 RouteSegment 列表
    }

    public record StationContext(KSDStation current,
                                 KSDStation previous,
                                 KSDStation next,
                                 KSDRoute route) {
    }

    public record StationState(KSDStation current,
                               KSDRoute route) {

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof StationState state) {
                return RailDataUtilities.isSameStation(current, state.current)
                        && RailDataUtilities.isSameRoute(route, state.route);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    RailDataUtilities.stationHashCode(current),
                    RailDataUtilities.routeHashCode(route));
        }
    }

    public record RouteSegment(@NotNull KSDStation from,
                               @NotNull KSDStation to,
                               KSDRoute route,
                               boolean hasFCService) {

        @Override
        public @NotNull String toString() {
            return "[RouteSegment: " + from.name + ", " + to.name + ", " + (route == null ? null : route.name) + ", " +  hasFCService + "]";
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RouteSegment segment) {
                return RailDataUtilities.isSameStation(from, segment.from)
                        && RailDataUtilities.isSameStation(to, segment.to)
                        && RailDataUtilities.isSameRoute(route, segment.route)
                        && hasFCService == segment.hasFCService;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    RailDataUtilities.stationHashCode(from),
                    RailDataUtilities.stationHashCode(to),
                    RailDataUtilities.routeHashCode(route),
                    Boolean.hashCode(hasFCService));
        }
    }
}
