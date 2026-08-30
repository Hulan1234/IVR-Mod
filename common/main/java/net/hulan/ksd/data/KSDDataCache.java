package net.hulan.ksd.data;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import mtr.data.NameColorDataBase;
import mtr.data.SavedRailBase;

import java.util.*;

public class KSDDataCache {

    private long lastRefreshedTime;
    public final Map<Long, KSDStation> stationIdMap = new HashMap<>();
    public final Map<Long, KSDPlatform> platformIdMap = new HashMap<>();
    public final Map<Long, KSDRoute> routeIdMap = new HashMap<>();
    public final Map<Long, KSDStation> platformIdToStation = new HashMap<>();
    public final Map<Long, Set<KSDPlatform>> stationIdToPlatforms = new HashMap<>();
    public final Map<Long, List<KSDStation>> routeIdToStationsWithIndex = new HashMap<>();
    public final Map<Long, Set<KSDRoute>> stationIdToRoutes = new HashMap<>();
    public final Map<KSDStation, Set<KSDStation>> stationIdToConnectingStations = new HashMap<>();
    public final Long2LongOpenHashMap blockPosToPlatformId = new Long2LongOpenHashMap();
    public final WayFinder wayFinder;
    protected final Set<KSDStation> stations;
    protected final Set<KSDPlatform> platforms;
    protected final Set<KSDRoute> routes;

    public KSDDataCache(Set<KSDStation> stations, Set<KSDPlatform> platforms, Set<KSDRoute> routes) {
        this.stations = stations;
        this.platforms = platforms;
        this.routes = routes;
        wayFinder = new WayFinder(this);
    }

    public final void sync() {
        try {
            mapIds(stationIdMap, stations);
            mapIds(platformIdMap, platforms);
            mapIds(routeIdMap, routes);
            routes.forEach(route -> route.platformIds.removeIf(platformId -> !platformIdMap.containsKey(platformId.platformId)));
            stationIdToConnectingStations.clear();
            stations.forEach(station1 -> {
                stationIdToConnectingStations.put(station1, new HashSet<>());
                stations.forEach(station2 -> {
                    if (station1 != station2 && station1.intersecting(station2)) {
                        stationIdToConnectingStations.get(station1).add(station2);
                    }
                });
            });
            mapSavedRailIdToStation(platformIdToStation, platforms, stations);
            mapAreaIdToSavedRails(stationIdToPlatforms, stations, platforms);
            routeIdToStationsWithIndex.clear();
            routes.forEach(r -> {
                if (!r.isHidden) {
                    for (int index = 0; index < r.platformIds.size(); index++) {
                        final KSDStation station = platformIdToStation.get(r.platformIds.get(index).platformId);
                        if (station != null) {
                            if (!routeIdToStationsWithIndex.containsKey(r.id)) {
                                routeIdToStationsWithIndex.put(r.id, new ArrayList<>(r.platformIds.size()));
                            }
                            routeIdToStationsWithIndex.get(r.id).add(station);
                        }
                    }
                }
            });
            stationIdToRoutes.clear();
            routes.forEach(route -> {
                if (!route.isHidden) {
                    route.platformIds.forEach(pId -> {
                        final KSDStation station = platformIdToStation.get(pId.platformId);
                        if (station != null) {
                            if (!stationIdToRoutes.containsKey(station.id)) {
                                stationIdToRoutes.put(station.id, new HashSet<>());
                            }
                            stationIdToRoutes.get(station.id).add(route);
                        }
                    });
                }
            });
            blockPosToPlatformId.clear();
            syncAdditional();
            wayFinder.sync();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        lastRefreshedTime = System.currentTimeMillis();
    }

    public boolean needsRefresh(long cachedRefreshTime) {
        return this.lastRefreshedTime > cachedRefreshTime;
    }

    protected void syncAdditional() {
    }

    protected static <U extends NameColorDataBase> void mapIds(Map<Long, U> map, Set<U> source) {
        map.clear();
        source.forEach((data) -> map.put(data.id, data));
    }

    private static <U extends SavedRailBase, V extends KSDAreaBase> void mapSavedRailIdToStation(Map<Long, V> map, Set<U> savedRails, Set<V> areas) {
        map.clear();
        savedRails.forEach(savedRail -> {
            for (final V area : areas) {
                if (area.isTransportMode(savedRail.transportMode) && area.inArea(savedRail.getMidPos())) {
                    map.put(savedRail.id, area);
                    break;
                }
            }
        });
    }

    private static <U extends KSDAreaBase, V extends SavedRailBase> void mapAreaIdToSavedRails(Map<Long, Set<V>> map, Set<U> areas, Set<V> savedRails) {
        map.clear();
        areas.forEach(area -> {
            for (final V savedRail : savedRails) {
                if (area.isTransportMode(savedRail.transportMode) && area.inArea(savedRail.getMidPos())) {
                    if (!map.containsKey(area.id)) {
                        map.put(area.id, new HashSet<>());
                    }
                    map.get(area.id).add(savedRail);
                }
            }
        });
    }
}
