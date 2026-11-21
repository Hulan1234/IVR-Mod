package net.hulan.ksd.path;

import mtr.client.ClientData;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.Route;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDAreaBase;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.SingleTicketSystem;
import net.hulan.ksd.sreen.KSDGui;
import net.hulan.ksd.utils.RailDataUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 线路图布局与线路路径生成器。
 *
 * <p>该生成器把原 {@code KCRSTMachineRailMap} 中的站点布局、避让和线路曲线算法集中起来，
 * 输出布局像素坐标（{@link KSDPath.CoordinateSpace#LAYOUT}）的路径以及渲染所需的站点数据。
 * 视图变换由渲染层通过 {@link #setViewTransform(double, double, double, int, int)} 传入，
 * 变换或底层数据变化后只标记 dirty，下次读取时懒重建。</p>
 */
public class KSDRailMapGenerator implements KSDPathProvider {

    /** 全局实例；线路图组件与客户端数据同步都使用它。 */
    public static final KSDRailMapGenerator INSTANCE = new KSDRailMapGenerator();

    /** 每段三次贝塞尔的采样段数（仅影响展开后的折线点数）。 */
    private static final int SAMPLES_PER_CURVE = 8;

    /** 贝塞尔控制点长度相对站距的比例。 */
    private static final double BEZIER_CONTROL_RATIO = 1.0 / 3.0;

    /** 小于该布局距离的相邻站不再生成线路段。 */
    private static final double MIN_STATION_DISTANCE = 0.001;

    /** 两端切线夹角正弦绝对值小于该值时退化为直线段。 */
    private static final double PARALLEL_TANGENT_EPSILON = 0.02;

    /** 查找平台附近轨道时的最大评分距离（水平距离 + 0.25 * 高度差）。 */
    private static final double RAIL_SEARCH_RADIUS = 24.0;

    /** 轨道空间索引的网格边长。 */
    private static final double RAIL_CELL_SIZE = 32.0;

    /** 轨道索引沿路径方向的采样份数，用于把长轨道登记到多个网格。 */
    private static final int RAIL_INDEX_SAMPLES = 8;

    private Long currentStationId;
    private SingleTicketSystem.TicketType ticketType;
    private boolean isLRT;

    private double scale = 0.05;
    private double centerX;
    private double centerY;
    private int width;
    private int height;

    private final Set<KSDRoute> mainRoutes = new HashSet<>();
    private final Set<KSDRoute> otherRoutes = new HashSet<>();
    private final Set<KSDRoute> lightRailRoutes = new HashSet<>();
    private final Set<KSDStation> mainStations = new HashSet<>();
    private final Set<KSDStation> otherStations = new HashSet<>();
    private final Set<KSDStation> lightRailStations = new HashSet<>();
    private final Map<Long, List<RailMapStation>> layouts = new HashMap<>();

    private final List<StationNode> stationNodes = new ArrayList<>();
    private final List<StationCircle> stationCircles = new ArrayList<>();
    private final List<InterchangeCapsule> interchangeCapsules = new ArrayList<>();
    private final List<Tuple<Float, Float>> logicalStationCenters = new ArrayList<>();
    private final Map<Long, Integer> mainStationColors = new HashMap<>();

    /** 布局避让使用的线路折线（每段线路一条，点数远少于渲染采样点）。 */
    private final List<List<Tuple<Float, Float>>> drawingLines = new ArrayList<>();

    private final List<KSDPath> paths = new ArrayList<>();
    private final Map<KSDPath, Integer> pathColors = new IdentityHashMap<>();
    private final Map<Long, KSDPath> routePaths = new HashMap<>();

    private boolean dataDirty = true;
    private boolean buildDirty = true;

    /** 平台 id -> 轨道切线（单位向量 {x, z}，方向未定，取不到时为 null）。 */
    private final Map<Long, double[]> platformTangents = new HashMap<>();
    /** 轨道空间索引：网格 -> (轨道 -> 采样信息)。 */
    private Map<Long, Map<Rail, RailSample>> railIndex;

    public KSDRailMapGenerator() {
    }

    // ---------------------------------------------------------------------
    // 渲染层入口
    // ---------------------------------------------------------------------

    /**
     * 设置当前线路图上下文。只有站点或票种变化时才需要重新读取线路数据。
     */
    public void load(KSDStation currentStation, SingleTicketSystem.TicketType ticketType) {
        final Long stationId = currentStation == null ? null : currentStation.id;
        final boolean changed = !Objects.equals(currentStationId, stationId) || this.ticketType != ticketType;
        this.currentStationId = stationId;
        this.ticketType = ticketType;
        this.isLRT = ticketType == SingleTicketSystem.TicketType.LRT;
        if (changed) {
            dataDirty = true;
        }
        buildDirty = true;
    }

    /**
     * 设置线路图组件的视图变换；值变化时把布局标记为 dirty。
     */
    public void setViewTransform(double scale, double centerX, double centerY, int width, int height) {
        if (this.scale != scale || this.centerX != centerX || this.centerY != centerY
                || this.width != width || this.height != height) {
            this.scale = scale;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            buildDirty = true;
        }
    }

    /** 把世界坐标转换为线路图布局坐标。 */
    public Tuple<Double, Double> worldPosToCords(double worldX, double worldZ) {
        final double cordsX = (worldX - centerX) * scale + (double) width / 2.0;
        final double cordsY = (worldZ - centerY) * scale + (double) height / 2.0;
        return new Tuple<>(cordsX, cordsY);
    }

    public boolean isLRT() {
        return isLRT;
    }

    /**
     * 返回渲染层需要展示站名的车站集合：主线路车站，轻铁图额外包含轻铁车站。
     */
    public Collection<KSDStation> getRenderableStations() {
        ensureBuilt();
        return collectRenderableStations().values();
    }

    /** 返回可交互的指定车站，找不到时返回 {@code null}。 */
    public KSDStation getRenderableStation(long stationId) {
        ensureBuilt();
        return findRenderableStation(stationId);
    }

    /** 返回当前所有线路路径（布局坐标）。 */
    @Override
    public List<KSDPath> getPaths() {
        ensureBuilt();
        return Collections.unmodifiableList(paths);
    }

    /** 返回指定线路的第一条路径；线路被拆分或不可绘制时可能为 {@code null}。 */
    @Override
    public KSDPath getPath(long routeId) {
        ensureBuilt();
        return routePaths.get(routeId);
    }

    /** 返回路径对应的绘制颜色。 */
    public int getPathColor(KSDPath path) {
        ensureBuilt();
        final Integer color = pathColors.get(path);
        return color == null ? KSDGui.ARGB_BLACK : color;
    }

    /**
     * 返回用于把路径展开成折线的采样步长。
     *
     * <p>按每段约 {@link #SAMPLES_PER_CURVE} 个采样段计算，因此无论缩放级别如何，
     * 单条路径的折线点数都只与站间段数有关。</p>
     */
    public double getSampleStep(KSDPath path) {
        ensureBuilt();
        final int segmentCount = Math.max(1, path.getSegments().size());
        final double length = path.getLength();
        return length <= 0 ? 1.0 : length / (SAMPLES_PER_CURVE * (double) segmentCount);
    }

    public List<StationNode> getStationNodes() {
        ensureBuilt();
        return Collections.unmodifiableList(stationNodes);
    }

    public List<StationCircle> getStationCircles() {
        ensureBuilt();
        return Collections.unmodifiableList(stationCircles);
    }

    public List<InterchangeCapsule> getInterchangeCapsules() {
        ensureBuilt();
        return Collections.unmodifiableList(interchangeCapsules);
    }

    public List<Tuple<Float, Float>> getLogicalStationCenters() {
        ensureBuilt();
        return Collections.unmodifiableList(logicalStationCenters);
    }

    public Map<Long, Integer> getMainStationColors() {
        ensureBuilt();
        return Collections.unmodifiableMap(mainStationColors);
    }

    /** 丢弃布局与路径缓存；下一次读取时重新从 {@link KSDClientData} 构建。 */
    @Override
    public void invalidate() {
        dataDirty = true;
        buildDirty = true;
        clearBuildData();
        layouts.clear();
        railIndex = null;
        platformTangents.clear();
    }

    // ---------------------------------------------------------------------
    // 懒构建
    // ---------------------------------------------------------------------

    private void ensureBuilt() {
        if (!buildDirty) {
            return;
        }
        if (dataDirty) {
            loadRoutes();
            loadStations();
            loadLayout();
        }
        buildRenderData();
        dataDirty = false;
        buildDirty = false;
    }

    private void clearBuildData() {
        stationNodes.clear();
        stationCircles.clear();
        interchangeCapsules.clear();
        logicalStationCenters.clear();
        mainStationColors.clear();
        drawingLines.clear();
        paths.clear();
        pathColors.clear();
        routePaths.clear();
    }

    // ---------------------------------------------------------------------
    // 线路与站点数据
    // ---------------------------------------------------------------------

    private void loadRoutes() {
        mainRoutes.clear();
        otherRoutes.clear();
        lightRailRoutes.clear();
        if (ticketType == null) {
            return;
        }
        final Set<KSDRoute> routesInNetwork = currentStationId == null
                ? Set.of() : KSDClientData.DATA_CACHE.pathFinder.getNetwork(currentStationId);
        final Set<KSDRoute> mtr = RailDataUtilities.getMTRRoutes(routesInNetwork);
        final Set<KSDRoute> kcr = RailDataUtilities.getKCRRoutes(routesInNetwork);
        final Set<KSDRoute> lightRail = RailDataUtilities.getLightRailRoutes(routesInNetwork);
        switch (ticketType) {
            case MTR -> {
                mainRoutes.addAll(mtr);
                otherRoutes.addAll(kcr);
                lightRailRoutes.addAll(lightRail);
            }
            case KCR, LRT -> {
                mainRoutes.addAll(kcr);
                otherRoutes.addAll(mtr);
                lightRailRoutes.addAll(lightRail);
            }
        }
    }

    private void loadStations() {
        mainStations.clear();
        otherStations.clear();
        lightRailStations.clear();
        final KSDRouteFinder pathFinder = KSDClientData.DATA_CACHE.pathFinder;
        for (KSDRoute route : mainRoutes) {
            mainStations.addAll(pathFinder.routeIdToStationsWithIndex.getOrDefault(route.id, List.of()));
        }
        for (KSDRoute route : otherRoutes) {
            otherStations.addAll(pathFinder.routeIdToStationsWithIndex.getOrDefault(route.id, List.of()));
        }
        for (KSDRoute route : lightRailRoutes) {
            lightRailStations.addAll(pathFinder.routeIdToStationsWithIndex.getOrDefault(route.id, List.of()));
        }
    }

    private Map<Long, KSDStation> collectRenderableStations() {
        final Map<Long, KSDStation> stationsById = new LinkedHashMap<>();
        mainStations.forEach(station -> stationsById.put(station.id, station));
        if (isLRT) {
            lightRailStations.forEach(station -> stationsById.putIfAbsent(station.id, station));
        }
        return stationsById;
    }

    private KSDStation findRenderableStation(long stationId) {
        for (KSDStation station : mainStations) {
            if (station.id == stationId) {
                return station;
            }
        }
        if (isLRT) {
            for (KSDStation station : lightRailStations) {
                if (station.id == stationId) {
                    return station;
                }
            }
        }
        return null;
    }

    /** 计算线路图布局：每条线路的站点顺序、平台编号、方向与站内分流序号。 */
    private void loadLayout() {
        layouts.clear();
        final Map<Long, List<RailMapStation>> railMapStationsByRoute = new HashMap<>();
        final Map<Long, Map<Integer, List<String>>> stationRouteDirections = new HashMap<>();
        final Map<Long, String> routeLineKeys = new HashMap<>();

        for (KSDRoute route : getAllRoutes()) {
            final List<KSDStation> stationList = new ArrayList<>();
            final List<Long> platformIdList = new ArrayList<>();
            for (Route.RoutePlatform routePlatform : route.platformIds) {
                final KSDStation station = KSDClientData.DATA_CACHE.pathFinder.platformIdToStation.get(routePlatform.platformId);
                if (KSDAreaBase.nonNullCorners(station) && isStationInRouteGroup(route, station)) {
                    if (!stationList.isEmpty() && RailDataUtilities.equals(stationList.get(stationList.size() - 1), station)) {
                        continue;
                    }
                    stationList.add(station);
                    platformIdList.add(routePlatform.platformId);
                }
            }
            if (stationList.size() < 2) {
                continue;
            }
            final String lineKey = RailDataUtilities.getRouteKey(route);
            routeLineKeys.put(route.id, lineKey);
            final List<RailMapStation> railMapStations = new ArrayList<>();
            for (int i = 0; i < stationList.size(); i++) {
                final RailMapStation railMapStation = new RailMapStation(stationList.get(i), platformIdList.get(i));
                railMapStation.direction = stationDirection(stationList, i);
                railMapStations.add(railMapStation);
                final List<String> lineKeys = stationRouteDirections.computeIfAbsent(stationList.get(i).id, key -> new HashMap<>())
                        .computeIfAbsent(railMapStation.direction, key -> new ArrayList<>());
                if (!lineKeys.contains(lineKey)) {
                    lineKeys.add(lineKey);
                }
            }
            railMapStationsByRoute.put(route.id, railMapStations);
        }

        for (Map.Entry<Long, List<RailMapStation>> entry : railMapStationsByRoute.entrySet()) {
            final long routeId = entry.getKey();
            for (RailMapStation railMapStation : entry.getValue()) {
                final List<String> lineKeys = stationRouteDirections.get(railMapStation.station.id).get(railMapStation.direction);
                lineKeys.sort(String::compareTo);
                railMapStation.offsetIndex = lineKeys.indexOf(routeLineKeys.get(routeId));
                railMapStation.routeCount = lineKeys.size();
            }
        }
        layouts.putAll(railMapStationsByRoute);
    }

    // ---------------------------------------------------------------------
    // 布局与曲线构建
    // ---------------------------------------------------------------------

    private void buildRenderData() {
        clearBuildData();
        final Map<Long, Map<String, Tuple<Float, Float>>> endpointsByStation = new HashMap<>();
        final Map<Long, List<Tuple<Float, Float>>> centersByStation = new HashMap<>();
        final Map<Long, List<StationRouteEndpoint>> routeEndpointsByStation = new HashMap<>();
        final Map<Long, Integer> routeCountsByStation = countRoutesByStation();
        final Map<Long, Integer> mainRouteCountsByStation = countRoutesByStation(mainRoutes);
        final Map<Long, Integer> otherRouteCountsByStation = countRoutesByStation(otherRoutes);
        final Map<Long, Integer> lightRailRouteCountsByStation = countRoutesByStation(lightRailRoutes);

        final Set<Long> specialInterchangeStationIds = new HashSet<>();
        if (isLRT) {
            routeCountsByStation.forEach((stationId, count) -> {
                if (count > 1) {
                    specialInterchangeStationIds.add(stationId);
                }
            });
        } else if (ticketType == SingleTicketSystem.TicketType.KCR) {
            mainRouteCountsByStation.forEach((stationId, count) -> {
                if (count > 0 && (otherRouteCountsByStation.getOrDefault(stationId, 0) > 0
                        || lightRailRouteCountsByStation.getOrDefault(stationId, 0) > 0)) {
                    specialInterchangeStationIds.add(stationId);
                }
            });
        }
        final Set<Long> capsuleStationIds = new HashSet<>(specialInterchangeStationIds);
        routeCountsByStation.forEach((stationId, count) -> {
            if (count > 3) {
                capsuleStationIds.add(stationId);
            }
        });

        final Set<String> visibleCircleKeys = new HashSet<>();
        final Set<String> drawnPairs = new HashSet<>();

        // 严格按主线路、其他线路、轻轨线路的顺序逐条完成站点与线段布局。
        for (KSDRoute route : getAllRoutes()) {
            final List<RailMapStation> railMapStations = layouts.get(route.id);
            if (railMapStations == null || railMapStations.size() < 2) {
                continue;
            }
            final String lineKey = RailDataUtilities.getRouteKey(route);
            final boolean mainRoute = mainRoutes.contains(route) || isLRT && lightRailRoutes.contains(route);
            final int color = getDrawingColor(route);
            final List<Tuple<Float, Float>> routeEndpoints = new ArrayList<>();
            KSDPath currentPath = new KSDPath(KSDPath.CoordinateSpace.LAYOUT);

            // 先为本线路依次放置站点；候选位置只参考此前已经登记的线路和站点。
            for (RailMapStation railMapStation : railMapStations) {
                final long stationId = railMapStation.station.id;
                final Map<String, Tuple<Float, Float>> endpoints = endpointsByStation.computeIfAbsent(stationId, key -> new HashMap<>());
                Tuple<Float, Float> endpoint = endpoints.get(lineKey);
                final String circleKey = stationId + ":" + lineKey;
                if (endpoint == null) {
                    final Tuple<Float, Float> preferred = getPreferredStationPosition(railMapStation);
                    final List<Tuple<Float, Float>> sameStationCenters = centersByStation.get(stationId);
                    if (mainRoute && (sameStationCenters == null || sameStationCenters.isEmpty())) {
                        endpoint = preferred;
                    } else {
                        endpoint = chooseStationPosition(preferred, stationId, railMapStation.direction, centersByStation);
                    }
                    endpoints.put(lineKey, endpoint);
                    centersByStation.computeIfAbsent(stationId, key -> new ArrayList<>()).add(endpoint);
                    logicalStationCenters.add(endpoint);
                    if (mainRoute) {
                        mainStationColors.putIfAbsent(stationId, color);
                    }
                    routeEndpointsByStation.computeIfAbsent(stationId, key -> new ArrayList<>())
                            .add(new StationRouteEndpoint(lineKey, endpoint, railMapStation.direction, color));
                }
                routeEndpoints.add(endpoint);
                stationNodes.add(new StationNode(railMapStation.station, route.id, lineKey, color,
                        endpoint.getA(), endpoint.getB(), railMapStation.direction,
                        railMapStation.offsetIndex, railMapStation.routeCount));

                // 非胶囊站点按原逻辑为每条可见线路分别生成一个圆圈。
                if (!capsuleStationIds.contains(stationId)
                        && findRenderableStation(stationId) != null
                        && visibleCircleKeys.add(circleKey)) {
                    stationCircles.add(new StationCircle(stationId, endpoint.getA(), endpoint.getB(),
                            isLRT ? KSDGui.ARGB_BLACK : color));
                }
            }

            // 本线路站点确定后立即生成线段，供下一条线路的站点和线段避让。
            for (int i = 0; i < routeEndpoints.size() - 1; i++) {
                final RailMapStation startStation = railMapStations.get(i);
                final RailMapStation endStation = railMapStations.get(i + 1);
                final String pairKey = stationPairKey(startStation.station.id, endStation.station.id);
                if (!drawnPairs.add(lineKey + ":" + pairKey)) {
                    currentPath = flushPath(currentPath, route.id, color);
                    continue;
                }
                final Tuple<Float, Float> start = routeEndpoints.get(i);
                final Tuple<Float, Float> end = routeEndpoints.get(i + 1);
                final KSDPathSegment segment = createConnectionSegment(start, startStation, end, endStation);
                if (segment == null) {
                    currentPath = flushPath(currentPath, route.id, color);
                    continue;
                }
                currentPath.addSegment(segment);
                addToLayoutLines(segment);
            }
            flushPath(currentPath, route.id, color);
        }

        for (Map.Entry<Long, List<StationRouteEndpoint>> entry : routeEndpointsByStation.entrySet()) {
            final long stationId = entry.getKey();
            if (capsuleStationIds.contains(stationId) && findRenderableStation(stationId) != null) {
                final int color = getStationMarkerColor(stationId, entry.getValue().get(0).color);
                interchangeCapsules.add(createAxisAlignedCapsule(stationId, entry.getValue(), color));
            }
        }
    }

    /** 根据真实轨道方向（或 45 度快照回退）生成相邻两站之间的三次贝塞尔。 */
    private KSDPathSegment createConnectionSegment(Tuple<Float, Float> start, RailMapStation startStation,
                                                   Tuple<Float, Float> end, RailMapStation endStation) {
        final double startX = start.getA();
        final double startZ = start.getB();
        final double endX = end.getA();
        final double endZ = end.getB();
        final double deltaX = endX - startX;
        final double deltaZ = endZ - startZ;
        final double distance = Math.hypot(deltaX, deltaZ);
        if (distance < MIN_STATION_DISTANCE) {
            return null;
        }
        final double directionX = deltaX / distance;
        final double directionZ = deltaZ / distance;
        final double[] startTangent = resolveTangent(startStation, directionX, directionZ, true);
        final double[] endTangent = resolveTangent(endStation, directionX, directionZ, false);

        // 两端切线平行（同向或反向）时曲线会退化成直线或出现尖点，直接绘制直线段。
        final double cross = Math.abs(startTangent[0] * endTangent[1] - startTangent[1] * endTangent[0]);
        if (cross < PARALLEL_TANGENT_EPSILON) {
            return KSDPathSegment.line(startX, startZ, endX, endZ);
        }
        final double controlLength = distance * BEZIER_CONTROL_RATIO;
        return KSDPathSegment.bezier(
                startX, startZ,
                startX + startTangent[0] * controlLength, startZ + startTangent[1] * controlLength,
                endX + endTangent[0] * controlLength, endZ + endTangent[1] * controlLength,
                endX, endZ);
    }

    /**
     * 取站点对应轨道的切线；取不到时回退到现有 45 度 direction 快照。
     *
     * @param towardNext true 表示切线应指向下一站，false 表示应指回上一站
     */
    private double[] resolveTangent(RailMapStation station, double directionX, double directionZ, boolean towardNext) {
        double[] tangent = tangentForPlatform(station.platformId);
        if (tangent == null) {
            final double radians = Math.toRadians(station.direction);
            tangent = new double[]{Math.cos(radians), Math.sin(radians)};
        }
        final double dot = tangent[0] * directionX + tangent[1] * directionZ;
        if (towardNext ? dot < 0 : dot > 0) {
            tangent = new double[]{-tangent[0], -tangent[1]};
        }
        return tangent;
    }

    /** 把一段曲线登记为布局避让使用的低密度折线。 */
    private void addToLayoutLines(KSDPathSegment segment) {
        final List<Tuple<Float, Float>> points = new ArrayList<>();
        if (segment.getType() == KSDPathSegment.Type.LINE) {
            addLayoutPoint(points, segment.sample(0));
            addLayoutPoint(points, segment.sample(1));
        } else {
            addLayoutPoint(points, segment.sample(0));
            addLayoutPoint(points, segment.sample(1.0 / 3.0));
            addLayoutPoint(points, segment.sample(2.0 / 3.0));
            addLayoutPoint(points, segment.sample(1));
        }
        if (points.size() >= 2) {
            drawingLines.add(points);
        }
    }

    private static void addLayoutPoint(List<Tuple<Float, Float>> points, double[] point) {
        final Tuple<Float, Float> candidate = new Tuple<>((float) point[0], (float) point[1]);
        if (points.isEmpty() || dist(points.get(points.size() - 1), candidate) > 0.001) {
            points.add(candidate);
        }
    }

    private KSDPath flushPath(KSDPath path, long routeId, int color) {
        if (path == null || path.isEmpty()) {
            return new KSDPath(KSDPath.CoordinateSpace.LAYOUT);
        }
        paths.add(path);
        pathColors.put(path, color);
        routePaths.putIfAbsent(routeId, path);
        return new KSDPath(KSDPath.CoordinateSpace.LAYOUT);
    }

    // ---------------------------------------------------------------------
    // 轨道切线
    // ---------------------------------------------------------------------

    private double[] tangentForPlatform(long platformId) {
        if (platformId <= 0) {
            return null;
        }
        if (platformTangents.containsKey(platformId)) {
            return platformTangents.get(platformId);
        }
        final double[] tangent = resolvePlatformTangent(platformId);
        platformTangents.put(platformId, tangent);
        return tangent;
    }

    private double[] resolvePlatformTangent(long platformId) {
        final KSDPlatform platform = KSDClientData.DATA_CACHE.platformIdMap.get(platformId);
        if (platform == null) {
            return null;
        }
        final BlockPos platformPos = platform.getMidPos();
        if (platformPos == null) {
            return null;
        }
        ensureRailIndex();
        final double platformX = platformPos.getX() + 0.5;
        final double platformY = platformPos.getY() + 0.5;
        final double platformZ = platformPos.getZ() + 0.5;
        final int minCellX = (int) Math.floor((platformX - RAIL_SEARCH_RADIUS) / RAIL_CELL_SIZE);
        final int maxCellX = (int) Math.floor((platformX + RAIL_SEARCH_RADIUS) / RAIL_CELL_SIZE);
        final int minCellZ = (int) Math.floor((platformZ - RAIL_SEARCH_RADIUS) / RAIL_CELL_SIZE);
        final int maxCellZ = (int) Math.floor((platformZ + RAIL_SEARCH_RADIUS) / RAIL_CELL_SIZE);
        RailSample nearest = null;
        double nearestScore = Double.MAX_VALUE;
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                final Map<Rail, RailSample> cell = railIndex.get(cellKey(cellX, cellZ));
                if (cell == null) {
                    continue;
                }
                for (RailSample sample : cell.values()) {
                    final double score = sample.score(platformX, platformY, platformZ);
                    if (score < nearestScore) {
                        nearestScore = score;
                        nearest = sample;
                    }
                }
            }
        }
        if (nearest == null || nearestScore > RAIL_SEARCH_RADIUS) {
            return null;
        }
        final double startDistance = distance(platformX, platformY, platformZ,
                nearest.startX, nearest.startY, nearest.startZ);
        final double endDistance = distance(platformX, platformY, platformZ,
                nearest.endX, nearest.endY, nearest.endZ);
        final RailAngle angle = startDistance <= endDistance ? nearest.rail.facingStart : nearest.rail.facingEnd;
        if (angle == null) {
            return null;
        }
        final double cos = angle.cos;
        final double sin = angle.sin;
        final double magnitude = Math.hypot(cos, sin);
        if (magnitude < 1e-9) {
            return null;
        }
        return new double[]{cos / magnitude, sin / magnitude};
    }

    private void ensureRailIndex() {
        if (railIndex != null) {
            return;
        }
        final Map<Long, Map<Rail, RailSample>> index = new HashMap<>();
        for (Map<BlockPos, Rail> rails : ClientData.RAILS.values()) {
            for (Rail rail : rails.values()) {
                if (rail == null) {
                    continue;
                }
                final double railLength = rail.getLength();
                if (!(railLength > 0)) {
                    continue;
                }
                final Vec3 start = rail.getPosition(0);
                final Vec3 end = rail.getPosition(railLength);
                final Vec3 middle = rail.getPosition(railLength / 2.0);
                final RailSample sample = new RailSample(rail, start, end, middle);
                for (int i = 0; i <= RAIL_INDEX_SAMPLES; i++) {
                    final Vec3 point = i == 0 ? start : i == RAIL_INDEX_SAMPLES ? end
                            : rail.getPosition(railLength * i / RAIL_INDEX_SAMPLES);
                    final int cellX = (int) Math.floor(point.x / RAIL_CELL_SIZE);
                    final int cellZ = (int) Math.floor(point.z / RAIL_CELL_SIZE);
                    index.computeIfAbsent(cellKey(cellX, cellZ), key -> new IdentityHashMap<>()).put(rail, sample);
                }
            }
        }
        railIndex = index;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        final double dx = x1 - x2;
        final double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(y1 - y2) * 0.25;
    }

    // ---------------------------------------------------------------------
    // 线路集合与站点统计
    // ---------------------------------------------------------------------

    /** Returns routes in deterministic drawing priority: main network, other network, then light rail. */
    private List<KSDRoute> getAllRoutes() {
        final List<KSDRoute> routes = new ArrayList<>();
        final KSDRouteFinder pathFinder = KSDClientData.DATA_CACHE.pathFinder;
        addSortedRoutes(routes, mainRoutes, pathFinder);
        addSortedRoutes(routes, otherRoutes, pathFinder);
        addSortedRoutes(routes, lightRailRoutes, pathFinder);
        return routes;
    }

    /** Adds one route group in stable key/id order so incremental collision decisions remain reproducible. */
    private static void addSortedRoutes(List<KSDRoute> target, Set<KSDRoute> source, KSDRouteFinder pathFinder) {
        final List<KSDRoute> sorted = new ArrayList<>(source.stream()
                .filter(route -> pathFinder.routeIdToStationsWithIndex.containsKey(route.id)
                        && pathFinder.routeIdToStationsWithIndex.get(route.id).size() >= 2)
                .toList());
        sorted.sort(Comparator.comparing(RailDataUtilities::getRouteKey).thenComparingLong(route -> route.id));
        target.addAll(sorted);
    }

    private Map<Long, Integer> countRoutesByStation() {
        return countRoutesByStation(getAllRoutes());
    }

    private Map<Long, Integer> countRoutesByStation(Collection<KSDRoute> routes) {
        final Map<Long, Set<String>> routeKeysByStation = new HashMap<>();
        for (KSDRoute route : routes) {
            final List<RailMapStation> stations = layouts.get(route.id);
            if (stations == null) {
                continue;
            }
            final String lineKey = RailDataUtilities.getRouteKey(route);
            final Set<Long> stationIds = new HashSet<>();
            for (RailMapStation station : stations) {
                if (stationIds.add(station.station.id)) {
                    routeKeysByStation.computeIfAbsent(station.station.id, key -> new HashSet<>()).add(lineKey);
                }
            }
        }
        final Map<Long, Integer> routeCounts = new HashMap<>();
        routeKeysByStation.forEach((stationId, lineKeys) -> routeCounts.put(stationId, lineKeys.size()));
        return routeCounts;
    }

    private boolean isStationInRouteGroup(KSDRoute route, KSDStation station) {
        if (mainRoutes.contains(route)) {
            return mainStations.contains(station);
        }
        if (otherRoutes.contains(route)) {
            return otherStations.contains(station);
        }
        if (lightRailRoutes.contains(route)) {
            return lightRailStations.contains(station);
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // 站点位置与避让（原 KCRSTMachineRailMap 算法）
    // ---------------------------------------------------------------------

    /** Converts the station selection center into local map coordinates. */
    private Tuple<Float, Float> getPreferredStationPosition(RailMapStation railMapStation) {
        final KSDStation station = railMapStation.station;
        final BlockPos pos = station.getCenter();
        final double worldX = pos.getX();
        final double worldZ = pos.getZ();
        final Tuple<Double, Double> point = worldPosToCords(worldX, worldZ);
        return new Tuple<>(point.getA().floatValue(), point.getB().floatValue());
    }

    /** Places an interchange circle with axis-aligned grouping before falling back to route-aware offsets. */
    private Tuple<Float, Float> chooseStationPosition(Tuple<Float, Float> preferred, long stationId, int direction,
                                                      Map<Long, List<Tuple<Float, Float>>> centersByStation) {
        final List<Tuple<Float, Float>> sameStationCenters = centersByStation.get(stationId);
        if (sameStationCenters == null || sameStationCenters.isEmpty()) {
            return findFreeStationPos(preferred, stationId, direction, centersByStation, null);
        }

        Tuple<Float, Float> best = null;
        double bestScore = Double.MAX_VALUE;
        final double spacing = KSDGui.RADIUS * 2;
        final int maxRing = Math.max(8, sameStationCenters.size() + 1);

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float sumX = 0;
        float sumY = 0;
        for (Tuple<Float, Float> center : sameStationCenters) {
            minX = Math.min(minX, center.getA());
            maxX = Math.max(maxX, center.getA());
            minY = Math.min(minY, center.getB());
            maxY = Math.max(maxY, center.getB());
            sumX += center.getA();
            sumY += center.getB();
        }
        final boolean preferHorizontal = maxX - minX >= maxY - minY;
        for (int axisPass = 0; axisPass < 2 && best == null; axisPass++) {
            final boolean horizontal = (axisPass == 0) == preferHorizontal;
            final float cross = horizontal ? sumY / sameStationCenters.size() : sumX / sameStationCenters.size();
            final float axisMin = horizontal ? minX : minY;
            final float axisMax = horizontal ? maxX : maxY;
            for (int ring = 1; ring <= maxRing && best == null; ring++) {
                final double ringDistance = spacing * ring;
                for (int sign : new int[]{-1, 1}) {
                    final float axis = sign < 0 ? (float) (axisMin - ringDistance) : (float) (axisMax + ringDistance);
                    final Tuple<Float, Float> candidate = horizontal
                            ? new Tuple<>(axis, cross) : new Tuple<>(cross, axis);
                    if (!checkIsOverlapped(candidate, sameStationCenters, spacing)) {
                        continue;
                    }
                    final double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        // 共线候选受阻时，仍优先使用水平/竖直轴向的局部位置。
        if (best == null) {
            for (Tuple<Float, Float> center : sameStationCenters) {
                for (int ring = 1; ring <= maxRing; ring++) {
                    final double ringDistance = spacing * ring;
                    for (boolean horizontal : new boolean[]{true, false}) {
                        for (int sign : new int[]{-1, 1}) {
                            final Tuple<Float, Float> candidate = offsetByAxis(center, ringDistance, horizontal, sign);
                            if (!checkIsOverlapped(candidate, sameStationCenters, spacing)) {
                                continue;
                            }
                            final double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters);
                            if (score < bestScore) {
                                bestScore = score;
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }

        // 若轴向候选都不可用，再沿本线路法线两侧寻找位置。
        double directionalBestScore = Double.MAX_VALUE;
        Tuple<Float, Float> directionalBest = null;
        for (Tuple<Float, Float> center : sameStationCenters) {
            for (int ring = 1; ring <= maxRing; ring++) {
                for (int sign : new int[]{-1, 1}) {
                    final Tuple<Float, Float> candidate = offsetByRouteDirection(center, direction, spacing * ring, true, sign);
                    if (!checkIsOverlapped(candidate, sameStationCenters, spacing)) {
                        continue;
                    }
                    final double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters);
                    if (score < directionalBestScore) {
                        directionalBestScore = score;
                        directionalBest = candidate;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        return directionalBest == null
                ? findFreeStationPos(preferred, stationId, direction, centersByStation, sameStationCenters)
                : directionalBest;
    }

    private static Tuple<Float, Float> offsetByAxis(Tuple<Float, Float> center, double distance,
                                                    boolean horizontal, int sign) {
        return horizontal
                ? new Tuple<>((float) (center.getA() + sign * distance), center.getB())
                : new Tuple<>(center.getA(), (float) (center.getB() + sign * distance));
    }

    private static boolean checkIsOverlapped(Tuple<Float, Float> candidate,
                                             List<Tuple<Float, Float>> sameStationCenters,
                                             double spacing) {
        for (Tuple<Float, Float> center : sameStationCenters) {
            if (dist(candidate, center) < spacing - 0.001) {
                return false;
            }
        }
        return true;
    }

    /** Finds a free location, preferring route-normal movement over movement along the route. */
    private Tuple<Float, Float> findFreeStationPos(Tuple<Float, Float> preferred, long stationId, int direction,
                                                   Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                                   List<Tuple<Float, Float>> sameStationCenters) {
        Tuple<Float, Float> best = preferred;
        double bestScore = stationPositionScore(preferred, preferred, stationId, centersByStation, sameStationCenters);

        // 首选原锚点；冲突时先沿线路法线，再沿线路本身寻找最近空位。
        for (int ring = 1; ring <= 4; ring++) {
            final double ringDistance = ring * (KSDGui.RADIUS * 2 + 2);
            for (boolean normal : new boolean[]{true, false}) {
                for (int sign : new int[]{-1, 1}) {
                    final Tuple<Float, Float> candidate = offsetByRouteDirection(preferred, direction, ringDistance, normal, sign);
                    final double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters)
                            + (normal ? 0 : ringDistance * 10);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (bestScore < 1) {
                break;
            }
        }
        return best;
    }

    /** Produces a local point offset along a route or its perpendicular normal. */
    private static Tuple<Float, Float> offsetByRouteDirection(Tuple<Float, Float> center, int direction,
                                                              double distance, boolean normal, int sign) {
        final double[] offset = normal
                ? rotatePoint(0, sign * distance, direction)
                : rotatePoint(sign * distance, 0, direction);
        return new Tuple<>((float) (center.getA() + offset[0]), (float) (center.getB() + offset[1]));
    }

    /** Penalizes movement, station overlap, existing lines and capsules when choosing a station endpoint. */
    private double stationPositionScore(Tuple<Float, Float> candidate, Tuple<Float, Float> preferred, long stationId,
                                        Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                        List<Tuple<Float, Float>> sameStationCenters) {
        double score = dist(candidate, preferred);
        final double sameStationSpacing = KSDGui.RADIUS * 2;
        final double otherStationSpacing = KSDGui.RADIUS * 2 + 2;

        for (Map.Entry<Long, List<Tuple<Float, Float>>> entry : centersByStation.entrySet()) {
            for (Tuple<Float, Float> center : entry.getValue()) {
                final double distance = dist(candidate, center);
                final double required = entry.getKey() == stationId ? sameStationSpacing : otherStationSpacing;
                if (distance < required - 0.01) {
                    score += (required - distance) * 10000;
                }
            }
        }

        // 同站候选优先与至少一个既有圆心相切，不在圆组外留下明显缝隙。
        if (sameStationCenters != null && !sameStationCenters.isEmpty()) {
            double nearest = Double.MAX_VALUE;
            for (Tuple<Float, Float> center : sameStationCenters) {
                nearest = Math.min(nearest, dist(candidate, center));
            }
            score += Math.abs(nearest - sameStationSpacing) * 100;
        }

        final double lineClearance = KSDGui.RADIUS + KSDGui.LINE_WIDTH / 2 + 2;
        for (List<Tuple<Float, Float>> line : drawingLines) {
            for (int i = 0; i < line.size() - 1; i++) {
                final double distance = pointSegmentDistance(candidate, line.get(i), line.get(i + 1));
                if (distance < lineClearance) {
                    score += (lineClearance - distance) * 1000;
                }
            }
        }
        for (InterchangeCapsule capsule : interchangeCapsules) {
            final double distance = pointSegmentDistance(candidate, capsule.start, capsule.end);
            if (capsule.stationId != stationId && distance < lineClearance) {
                score += (lineClearance - distance) * 1000;
            }
        }
        return score;
    }

    // ---------------------------------------------------------------------
    // 换乘胶囊
    // ---------------------------------------------------------------------

    private static InterchangeCapsule createAxisAlignedCapsule(long stationId,
                                                               List<StationRouteEndpoint> endpoints,
                                                               int color) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (StationRouteEndpoint endpoint : endpoints) {
            minX = Math.min(minX, endpoint.center.getA());
            maxX = Math.max(maxX, endpoint.center.getA());
            minY = Math.min(minY, endpoint.center.getB());
            maxY = Math.max(maxY, endpoint.center.getB());
        }
        final CapsuleBounds horizontalBounds = createCapsuleBounds(minX, maxX, minY, maxY, endpoints.size(), true);
        final CapsuleBounds verticalBounds = createCapsuleBounds(minX, maxX, minY, maxY, endpoints.size(), false);
        final CapsuleBounds bounds = horizontalBounds.area <= verticalBounds.area ? horizontalBounds : verticalBounds;
        return new InterchangeCapsule(stationId, bounds.center, bounds.start, bounds.end,
                bounds.radius, bounds.horizontal, color);
    }

    private static CapsuleBounds createCapsuleBounds(float minX, float maxX, float minY, float maxY,
                                                     int routeCount, boolean horizontal) {
        final float axisMin = horizontal ? minX : minY;
        final float axisMax = horizontal ? maxX : maxY;
        final float crossMin = horizontal ? minY : minX;
        final float crossMax = horizontal ? maxY : maxX;
        final float crossSpan = crossMax - crossMin;
        final float radius = Math.max(KSDGui.RADIUS, crossSpan / 2 + KSDGui.RADIUS);
        final int crossCapacity = Math.max(1, (int) Math.floor(radius / KSDGui.RADIUS));
        final int axisCapacity = (int) Math.ceil((double) routeCount / crossCapacity);
        final float minimumAxisSpan = Math.max(0, (axisCapacity - 1) * KSDGui.RADIUS * 2);
        final float axisCenter = (axisMin + axisMax) / 2;
        final float crossCenter = (crossMin + crossMax) / 2;
        final float axisSpan = Math.max(axisMax - axisMin, minimumAxisSpan);
        final Tuple<Float, Float> center = horizontal ? new Tuple<>(axisCenter, crossCenter) : new Tuple<>(crossCenter, axisCenter);
        final Tuple<Float, Float> start = horizontal ? new Tuple<>(axisCenter - axisSpan / 2, crossCenter)
                : new Tuple<>(crossCenter, axisCenter - axisSpan / 2);
        final Tuple<Float, Float> end = horizontal ? new Tuple<>(axisCenter + axisSpan / 2, crossCenter)
                : new Tuple<>(crossCenter, axisCenter + axisSpan / 2);
        final double area = (axisSpan + radius * 2) * radius * 2;
        return new CapsuleBounds(center, start, end, radius, horizontal, area);
    }

    // ---------------------------------------------------------------------
    // 颜色与通用几何
    // ---------------------------------------------------------------------

    /** Applies category colors while preserving each main route's configured color. */
    private int getDrawingColor(KSDRoute route) {
        if (lightRailRoutes.contains(route)) {
            return KSDGui.LIGHT_RAIL_COLOR;
        }
        if (otherRoutes.contains(route)) {
            return argb(0x808080);
        }
        return argb(route.color);
    }

    private int getStationMarkerColor(long stationId, int fallbackColor) {
        if (isLRT) {
            return KSDGui.ARGB_BLACK;
        }
        return mainStationColors.getOrDefault(stationId, fallbackColor);
    }

    private static int argb(int color) {
        return 0xFF000000 | (color & 0xFFFFFF);
    }

    private static double dist(Tuple<Float, Float> corner1, Tuple<Float, Float> corner2) {
        return Math.sqrt(Math.pow(corner1.getA() - corner2.getA(), 2) + Math.pow(corner1.getB() - corner2.getB(), 2));
    }

    /** Returns the shortest distance between a point and a finite line segment. */
    private static double pointSegmentDistance(Tuple<Float, Float> point, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        final double dx = end.getA() - start.getA();
        final double dy = end.getB() - start.getB();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1E-6) {
            return dist(point, start);
        }
        double t = ((point.getA() - start.getA()) * dx + (point.getB() - start.getB()) * dy) / lengthSquared;
        t = Mth.clamp(t, 0, 1);
        final double x = start.getA() + t * dx;
        final double y = start.getB() + t * dy;
        return Math.hypot(point.getA() - x, point.getB() - y);
    }

    /** 把点 (x,y) 绕原点旋转 direction 度（仅支持 45 度倍数的快照角度）。 */
    private static double[] rotatePoint(double x, double y, int direction) {
        final int d = ((direction % 360) + 360) % 360;
        final double sin;
        final double cos = switch (d) {
            case 90 -> {
                sin = 1;
                yield 0;
            }
            case 270 -> {
                sin = -1;
                yield 0;
            }
            case 45 -> {
                sin = Math.sqrt(0.5);
                yield Math.sqrt(0.5);
            }
            case 135 -> {
                sin = Math.sqrt(0.5);
                yield -Math.sqrt(0.5);
            }
            case 225 -> {
                sin = -Math.sqrt(0.5);
                yield -Math.sqrt(0.5);
            }
            case 315 -> {
                sin = -Math.sqrt(0.5);
                yield Math.sqrt(0.5);
            }
            case 180 -> {
                sin = 0;
                yield -1;
            }
            default -> {
                sin = 0;
                yield 1;
            }
        };
        return new double[]{x * cos - y * sin, x * sin + y * cos};
    }

    /** 计算某站在线路中的走向角度并快照到 45 度倍数（0~135 之间）。 */
    private static int stationDirection(List<KSDStation> stationList, int index) {
        final BlockPos prev;
        final BlockPos next;
        if (index == 0) {
            prev = stationList.get(0).getCenter();
            next = stationList.get(1).getCenter();
        } else if (index == stationList.size() - 1) {
            prev = stationList.get(stationList.size() - 2).getCenter();
            next = stationList.get(stationList.size() - 1).getCenter();
        } else {
            prev = stationList.get(index - 1).getCenter();
            next = stationList.get(index + 1).getCenter();
        }
        if (prev == null || next == null) {
            return 0;
        }
        double angle = Math.toDegrees(Math.atan2(next.getZ() - prev.getZ(), next.getX() - prev.getX()));
        angle = ((angle % 180) + 180) % 180;
        int snapped = (int) Math.round(angle / 45) * 45;
        if (snapped >= 180) {
            snapped -= 180;
        }
        return snapped;
    }

    /** 生成无向的站点对 key（小的在前，大的在后）。 */
    private static String stationPairKey(long id1, long id2) {
        return Math.min(id1, id2) + "," + Math.max(id1, id2);
    }

    // ---------------------------------------------------------------------
    // 数据结构
    // ---------------------------------------------------------------------

    /** 线路图中的一个站点节点：站点、布局坐标、方向、线路标识与分流信息。 */
    public static final class StationNode {

        public final KSDStation station;
        public final long routeId;
        public final String lineKey;
        public final int color;
        public final float x;
        public final float y;
        public final int direction;
        public final int offsetIndex;
        public final int routeCount;

        public StationNode(KSDStation station, long routeId, String lineKey, int color,
                           float x, float y, int direction, int offsetIndex, int routeCount) {
            this.station = station;
            this.routeId = routeId;
            this.lineKey = lineKey;
            this.color = color;
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.offsetIndex = offsetIndex;
            this.routeCount = routeCount;
        }
    }

    /** 已解析的车站圆：站点 id、圆心位置与线路颜色。 */
    public static final class StationCircle {

        public final long stationId;
        public final float centerX;
        public final float centerY;
        public final int color;

        public StationCircle(long stationId, float centerX, float centerY, int color) {
            this.stationId = stationId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.color = color;
        }
    }

    /** 轴对齐的换乘胶囊。 */
    public static final class InterchangeCapsule {

        public final long stationId;
        public final Tuple<Float, Float> mainCenter;
        public final float radius;
        public final boolean horizontal;
        public final int color;
        public final Tuple<Float, Float> start;
        public final Tuple<Float, Float> end;

        public InterchangeCapsule(long stationId, Tuple<Float, Float> mainCenter, Tuple<Float, Float> start,
                                  Tuple<Float, Float> end, float radius, boolean horizontal, int color) {
            this.stationId = stationId;
            this.mainCenter = mainCenter;
            this.start = start;
            this.end = end;
            this.radius = radius;
            this.horizontal = horizontal;
            this.color = color;
        }
    }

    /** 线路图布局中的单个站点：记录所属站点、平台编号与站内分流信息。 */
    private static final class RailMapStation {

        final KSDStation station;
        final long platformId;
        int direction;
        int offsetIndex;
        int routeCount;

        RailMapStation(KSDStation station, long platformId) {
            this.station = station;
            this.platformId = platformId;
        }
    }

    private static final class StationRouteEndpoint {

        final String lineKey;
        final Tuple<Float, Float> center;
        final int direction;
        final int color;

        StationRouteEndpoint(String lineKey, Tuple<Float, Float> center, int direction, int color) {
            this.lineKey = lineKey;
            this.center = center;
            this.direction = direction;
            this.color = color;
        }
    }

    private static final class CapsuleBounds {

        final Tuple<Float, Float> center;
        final Tuple<Float, Float> start;
        final Tuple<Float, Float> end;
        final float radius;
        final boolean horizontal;
        final double area;

        CapsuleBounds(Tuple<Float, Float> center, Tuple<Float, Float> start, Tuple<Float, Float> end,
                      float radius, boolean horizontal, double area) {
            this.center = center;
            this.start = start;
            this.end = end;
            this.radius = radius;
            this.horizontal = horizontal;
            this.area = area;
        }
    }

    /** 轨道采样信息，用于按平台位置查找最近的轨道。 */
    private static final class RailSample {

        final Rail rail;
        final double startX;
        final double startY;
        final double startZ;
        final double endX;
        final double endY;
        final double endZ;
        final double midX;
        final double midY;
        final double midZ;

        RailSample(Rail rail, Vec3 start, Vec3 end, Vec3 middle) {
            this.rail = rail;
            this.startX = start.x;
            this.startY = start.y;
            this.startZ = start.z;
            this.endX = end.x;
            this.endY = end.y;
            this.endZ = end.z;
            this.midX = middle.x;
            this.midY = middle.y;
            this.midZ = middle.z;
        }

        double score(double x, double y, double z) {
            return Math.min(
                    Math.min(distance(x, y, z, startX, startY, startZ), distance(x, y, z, endX, endY, endZ)),
                    distance(x, y, z, midX, midY, midZ));
        }
    }
}
