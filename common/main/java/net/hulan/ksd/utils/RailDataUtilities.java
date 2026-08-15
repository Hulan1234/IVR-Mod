package net.hulan.ksd.utils;

import mtr.data.NameColorDataBase;
import net.hulan.ksd.client.KSDClientCache;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class RailDataUtilities {

    public static boolean isSameStation(KSDStation station1, KSDStation station2) {
        if (station1 == null || station2 == null) {
            return false;
        }
        String mainName1 = getMainName(station1);
        String mainName2 = getMainName(station2);
        return mainName1.equals(mainName2) && station1.color == station2.color;
    }

    public static boolean isSameRoute(KSDRoute route1, KSDRoute route2) {
        if (route1 == null || route2 == null) {
            return false;
        }
        String mainName1 = getMainName(route1);
        String mainName2 = getMainName(route2);
        return mainName1.equals(mainName2) && route1.color == route2.color;
    }

    public static String getMainName(@NotNull NameColorDataBase data) {
        return data.name.split("\\|\\|")[0];
    }

    public static String[] getSplitName(@NotNull NameColorDataBase data) {
        return getMainName(data).split("\\|");
    }

    public static Set<KSDRoute> getRoutesInSameRailNet(KSDClientCache clientCache, KSDRoute route) {
        Set<KSDStation> stationsInRoute = getStationsInRoute(clientCache, route);
        Set<KSDRoute> routesInSameRailNet = new HashSet<>();
        for (KSDStation station : stationsInRoute) {
            routesInSameRailNet.addAll(DataUtilities.getNonNullSetFromDataCollection(clientCache.stationIdToRoutes.get(station.id)));
        }
        return routesInSameRailNet;
    }

    public static Set<KSDStation> getStationsInRoute(KSDClientCache clientCache, KSDRoute route) {
        return DataUtilities.getMappedAndNonNullSetFromDataCollection(route.platformIds, rp -> clientCache.platformIdToStation.get(rp.platformId));
    }
}
