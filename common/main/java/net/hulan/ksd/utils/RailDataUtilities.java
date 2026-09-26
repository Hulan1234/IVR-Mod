package net.hulan.ksd.utils;

import mtr.data.NameColorDataBase;
import mtr.data.RouteType;
import net.hulan.ksd.data.KSDRoute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

public class RailDataUtilities {

    public static boolean equals(@Nullable NameColorDataBase b1, @Nullable NameColorDataBase b2) {
        if (b1 == b2) {
            return true;
        }
        if (b1 == null || b2 == null) {
            return false;
        }
        return b1.id == b2.id;
    }

    public static int hashCode(@Nullable NameColorDataBase b1) {
        if (b1 == null) {
            return Long.hashCode(0);
        }
        return Long.hashCode(b1.id);
    }

    public static boolean isSameRoute(KSDRoute route1, KSDRoute route2) {
        if (route1 == null || route2 == null) {
            return false;
        }
        return Objects.equals(getRouteKey(route1), getRouteKey(route2));
    }

    public static String getMainName(@NotNull NameColorDataBase data) {
        return data.name.split("\\|\\|")[0];
    }

    public static String[] getSplitName(@NotNull NameColorDataBase data) {
        return getMainName(data).split("\\|");
    }

    public static String getRouteKey(@NotNull KSDRoute route) {
        return getMainName(route) + "\u0000" + route.color;
    }

    public static Set<KSDRoute> getMTRRoutes(Set<KSDRoute> routes) {
        return DataUtilities.filterToSet(routes, RailDataUtilities::isMTRRoute);
    }

    public static Set<KSDRoute> getKCRRoutes(Set<KSDRoute> routes) {
        return DataUtilities.filterToSet(routes, RailDataUtilities::isKCRRoute);
    }

    public static Set<KSDRoute> getLightRailRoutes(Set<KSDRoute> routes) {
        return DataUtilities.filterToSet(routes, RailDataUtilities::isLightRailRoute);
    }

    public static boolean isMTRRoute(KSDRoute route) {
        return route.routeType.equals(RouteType.NORMAL);
    }

    public static boolean isKCRRoute(KSDRoute route) {
        return route.routeType.equals(Utilities.KCR);
    }

    public static boolean isLightRailRoute(KSDRoute route) {
        return route.routeType.equals(RouteType.LIGHT_RAIL) && route.isLightRailRoute;
    }

    public static boolean hasFirstClassService(KSDRoute route) {
        return isKCRRoute(route) && route.hasFirstClassService;
    }
}
