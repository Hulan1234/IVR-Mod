package net.hulan.ksd.utils;

import mtr.data.NameColorDataBase;
import mtr.data.RouteType;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

public class RailDataUtilities {

    public static boolean isSameStation(KSDStation station1, KSDStation station2) {
        if (station1 == null || station2 == null) {
            return false;
        }
        return Objects.equals(getStationKey(station1), getStationKey(station2));
    }

    public static boolean isSameRoute(KSDRoute route1, KSDRoute route2) {
        if (route1 == null || route2 == null) {
            return false;
        }
        return Objects.equals(getRouteKey(route1), getRouteKey(route2));
    }

    public static int stationHashCode(KSDStation station) {
        return getStationKey(station).hashCode();
    }

    public static int routeHashCode(KSDRoute route) {
        return getRouteKey(route).hashCode();
    }

    public static String getMainName(@NotNull NameColorDataBase data) {
        return data.name.split("\\|\\|")[0];
    }

    public static String[] getSplitName(@NotNull NameColorDataBase data) {
        return getMainName(data).split("\\|");
    }

    public static String getStationKey(@NotNull KSDStation station) {
        return getMainName(station) + "\u0000" + station.color;
    }

    public static String getRouteKey(@NotNull KSDRoute route) {
        return getMainName(route) + "\u0000" + route.color;
    }

    public static Set<KSDRoute> getMTRRoutes(Set<KSDRoute> routes) {
        return DataUtilities.getFilteredSetFromDataCollection(routes, RailDataUtilities::isMTRRoute);
    }

    public static Set<KSDRoute> getKCRRoutes(Set<KSDRoute> routes) {
        return DataUtilities.getFilteredSetFromDataCollection(routes, RailDataUtilities::isKCRRoute);
    }

    public static Set<KSDRoute> getLightRailRoutes(Set<KSDRoute> routes) {
        return DataUtilities.getFilteredSetFromDataCollection(routes, RailDataUtilities::isLightRailRoute);
    }

    public static boolean isMTRRoute(KSDRoute route) {
        return route.routeType.equals(RouteType.NORMAL);
    }

    public static boolean isKCRRoute(KSDRoute route) {
        return route.routeType.equals(Utilities.KCR_CLASSICAL) || route.routeType.equals(Utilities.KCR_MODERN);
    }

    public static boolean isLightRailRoute(KSDRoute route) {
        return route.routeType.equals(RouteType.LIGHT_RAIL) && route.isLightRailRoute;
    }

    public static boolean hasFirstClassService(KSDRoute route) {
        return route.routeType.equals(Utilities.KCR_CLASSICAL) && route.hasFirstClassService;
    }
}
