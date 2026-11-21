package net.hulan.ksd.path;

import mtr.client.ClientData;
import mtr.data.Rail;
import mtr.data.Route;
import mtr.data.SavedRailBase;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 真实轨道曲线生成器（世界坐标）。
 *
 * <p>以 MTR 的轨道图 {@link ClientData#RAILS} 为数据源，用 MTR 的 {@link PathFinder} 求出每条线路
 * 相邻站台之间的轨道段：</p>
 * <ul>
 *     <li>{@link #getRailPolylines()}：所有轨道（含未接入线路的轨道）的采样折线与线路颜色，供地图绘制；</li>
 *     <li>{@link #getPaths()} / {@link #getPath(long)}：每条线路的 {@link KSDPath}（世界坐标）。</li>
 * </ul>
 *
 * <p>数据在 {@link #invalidate()} 后按需重建；{@code KSDClientCache#syncAdditional()} 会调用它。</p>
 */
public final class KSDRoutePathGenerator implements KSDPathProvider {

    public static final KSDRoutePathGenerator INSTANCE = new KSDRoutePathGenerator();

    /** 轨道采样步长（世界方块）。 */
    public static final double SAMPLE_STEP = 0.5;

    private final Map<Long, KSDPath> pathsByRoute = new HashMap<>();
    private List<RailPolyline> railPolylines = Collections.emptyList();
    private boolean dirty = true;

    private KSDRoutePathGenerator() {
    }

    /** 一条轨道采样后的折线与其经过的线路颜色（按线路 id 升序；空表示未接入线路）。 */
    public static final class RailPolyline {
        private final List<double[]> points;
        private final List<Integer> colors;

        private RailPolyline(List<double[]> points, List<Integer> colors) {
            this.points = points;
            this.colors = colors;
        }

        /** {x, z} 点列表。 */
        public List<double[]> getPoints() {
            return points;
        }

        /** ARGB 颜色列表，同一段轨道有多条线路时按线路 id 升序排列。 */
        public List<Integer> getColors() {
            return colors;
        }
    }

    @Override
    public void invalidate() {
        this.dirty = true;
    }

    /** 所有轨道的绘制数据（含未接入线路的轨道）。 */
    public List<RailPolyline> getRailPolylines() {
        ensureBuilt();
        return railPolylines;
    }

    @Override
    public List<KSDPath> getPaths() {
        ensureBuilt();
        return new ArrayList<>(pathsByRoute.values());
    }

    @Override
    public KSDPath getPath(long routeId) {
        ensureBuilt();
        return pathsByRoute.get(routeId);
    }

    private void ensureBuilt() {
        if (!dirty) {
            return;
        }
        dirty = false;
        pathsByRoute.clear();
        if (ClientData.RAILS.isEmpty()) {
            railPolylines = Collections.emptyList();
            return;
        }
        final Map<RailKey, Rail> rails = collectRails();
        if (rails.isEmpty()) {
            railPolylines = Collections.emptyList();
            return;
        }
        // PathData.rail 就是 RAILS 表里的同一个实例，用 identity 索引回端点键
        final Map<Rail, RailKey> railKeys = new IdentityHashMap<>(rails.size());
        for (Map.Entry<RailKey, Rail> entry : rails.entrySet()) {
            railKeys.put(entry.getValue(), entry.getKey());
        }
        final Map<Long, KSDRoute> routesById = new HashMap<>();
        final Map<RailKey, TreeSet<Long>> routeIdsByRail = new HashMap<>();
        for (KSDRoute route : KSDClientData.ROUTES) {
            routesById.put(route.id, route);
            final List<SavedRailBase> platforms = new ArrayList<>();
            for (Route.RoutePlatform routePlatform : route.platformIds) {
                final KSDPlatform platform = KSDClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
                if (platform != null) {
                    platforms.add(platform);
                }
            }
            if (platforms.size() < 2) {
                continue;
            }
            final KSDPath path = new KSDPath(KSDPath.CoordinateSpace.WORLD);
            boolean anySegment = false;
            // 逐对相邻站台调用 PathFinder：某一段找不到路径时只丢失该段，其余段仍可绘制
            for (int i = 0; i + 1 < platforms.size(); i++) {
                final List<PathData> leg = new ArrayList<>();
                try {
                    PathFinder.findPath(leg, ClientData.RAILS,
                            Arrays.asList(platforms.get(i), platforms.get(i + 1)), 0, 0, false);
                } catch (RuntimeException exception) {
                    // 轨道图不完整时 PathFinder 可能抛出空指针，跳过这一段即可
                    leg.clear();
                }
                for (PathData pathData : leg) {
                    final RailKey key = railKeys.get(pathData.rail);
                    if (key == null) {
                        continue;
                    }
                    appendRailPoints(path, pathData.rail, pathData.startingPos);
                    routeIdsByRail.computeIfAbsent(key, k -> new TreeSet<>()).add(route.id);
                    anySegment = true;
                }
            }
            if (anySegment && !path.isEmpty()) {
                pathsByRoute.put(route.id, path);
            }
        }
        final List<RailPolyline> polylines = new ArrayList<>(rails.size());
        for (Map.Entry<RailKey, Rail> entry : rails.entrySet()) {
            final RailKey key = entry.getKey();
            final List<double[]> points = new ArrayList<>();
            sampleRail(points, entry.getValue(), key.a);
            if (points.size() < 2) {
                continue;
            }
            final List<Integer> colors = new ArrayList<>();
            final TreeSet<Long> routeIds = routeIdsByRail.get(key);
            if (routeIds != null) {
                for (Long routeId : routeIds) {
                    final KSDRoute route = routesById.get(routeId);
                    if (route != null) {
                        colors.add(0xFF000000 | (route.color & 0xFFFFFF));
                    }
                }
            }
            polylines.add(new RailPolyline(points, colors));
        }
        railPolylines = Collections.unmodifiableList(polylines);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (RailPolyline polyline : polylines) {
            for (double[] point : polyline.getPoints()) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]);
                maxZ = Math.max(maxZ, point[1]);
            }
        }
    }

    // 收集所有轨道，并用“端点无序对”作为唯一键去重（RAILS 里同一段轨道可能出现两次）
    private static Map<RailKey, Rail> collectRails() {
        final Map<RailKey, Rail> rails = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> fromEntry : ClientData.RAILS.entrySet()) {
            final BlockPos from = fromEntry.getKey();
            for (Map.Entry<BlockPos, Rail> toEntry : fromEntry.getValue().entrySet()) {
                final Rail rail = toEntry.getValue();
                if (rail == null) {
                    continue;
                }
                rails.putIfAbsent(new RailKey(from, toEntry.getKey()), rail);
            }
        }
        return rails;
    }

    // 把一段轨道按其起始方向采样并追加到线路路径
    private static void appendRailPoints(KSDPath path, Rail rail, BlockPos startPos) {
        final List<double[]> points = new ArrayList<>();
        sampleRail(points, rail, startPos);
        for (int i = 0; i + 1 < points.size(); i++) {
            final double[] from = points.get(i);
            final double[] to = points.get(i + 1);
            path.addSegment(KSDPathSegment.line(from[0], from[1], to[0], to[1]));
        }
    }

    // 采样一段轨道，起点尽量靠近 from
    // 注意：Rail.getPosition 的参数是“沿轨道的距离”而不是 0..1 的参数，
    // 必须先按 getLength() 取距离，否则只会画出起点附近最多 1 格
    private static void sampleRail(List<double[]> points, Rail rail, BlockPos from) {
        if (rail == null) {
            return;
        }
        final double length = rail.getLength();
        final int samples = Math.max(2, (int) Math.ceil(length / SAMPLE_STEP));
        final Vec3 start = rail.getPosition(0);
        final Vec3 end = rail.getPosition(length);
        final boolean reversed = squaredDistance(start, from) > squaredDistance(end, from);
        for (int i = 0; i <= samples; i++) {
            final double distance = length * i / (double) samples;
            final Vec3 position = rail.getPosition(reversed ? length - distance : distance);
            final double[] point = new double[]{position.x, position.z};
            if (points.isEmpty() || !isSamePoint(points.get(points.size() - 1), point)) {
                points.add(point);
            }
        }
    }

    private static boolean isSamePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0E-4 && Math.abs(a[1] - b[1]) < 1.0E-4;
    }

    private static double squaredDistance(Vec3 position, BlockPos blockPos) {
        final double dx = position.x - (blockPos.getX() + 0.5);
        final double dz = position.z - (blockPos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    // 轨道端点的无序对，保证同一段轨道只有一个键
    private static final class RailKey {
        private final BlockPos a;
        private final BlockPos b;

        private RailKey(BlockPos first, BlockPos second) {
            if (first.asLong() <= second.asLong()) {
                this.a = first;
                this.b = second;
            } else {
                this.a = second;
                this.b = first;
            }
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof RailKey other)) {
                return false;
            }
            return a.equals(other.a) && b.equals(other.b);
        }

        @Override
        public int hashCode() {
            return 31 * a.hashCode() + b.hashCode();
        }
    }
}
