package net.hulan.ksd.utils;

import net.hulan.ksd.data.FirstClassPlayer;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class DataUtilities {

    public static KSDStation getStation(Set<KSDStation> stations, long stationId) {
        return getFilteredValueFromDataSet(stations, s -> s.id == stationId);
    }

    public static KSDRoute getRoute(Set<KSDRoute> routes, long routeId) {
        return getFilteredValueFromDataSet(routes, r -> r.id == routeId);
    }

    public static KSDPlatform getPlatform(Set<KSDPlatform> platforms, long platformId) {
        return getFilteredValueFromDataSet(platforms, p -> p.id == platformId);
    }

    public static FirstClassPlayer getFirstClassPlayer(Set<FirstClassPlayer> fps, Player player) {
        return getFilteredValueFromDataSet(fps, f -> f.uuid.equals(player.getUUID()));
    }

    public static <T> T getFilteredValueFromDataSet(Set<T> dataSet, Predicate<T> filter) {
        return getFilteredValueFromDataSetWithDefaultValue(dataSet, filter, null);
    }

    public static <T> T getFilteredValueFromDataSetWithDefaultValue(Set<T> dataSet, Predicate<T> filter, T defaultValue) {
        return dataSet.stream().filter(filter).findFirst().orElse(defaultValue);
    }

    public static <T> void executeFromDataSet(Set<T> dataSet, Predicate<T> filter, Consumer<T> action) {
        dataSet.stream().filter(filter).findFirst().ifPresent(action);
    }

    public static <T> List<T> getFilteredListFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).toList();
    }

    public static <T> Set<T> getNonNullSetFromDataCollection(Collection<T> dataCollection) {
        return dataCollection.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public static <T> Set<T> getFilteredSetFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).collect(Collectors.toSet());
    }

    public static <T, R> List<R> getSortedAndMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().sorted().map(mapper).toList();
    }

    public static <T, R> List<R> getMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).toList();
    }

    public static <T, R> Set<R> getMappedSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).collect(Collectors.toSet());
    }

    public static <T, R> List<R> getMappedAndNonNullListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return getMappedAndFilteredListFromDataCollection(dataCollection, mapper, Objects::nonNull);
    }

    public static <T, R> Set<R> getMappedAndNonNullSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return getMappedAndFilteredSetFromDataCollection(dataCollection, mapper, Objects::nonNull);
    }

    public static <T, R> List<R> getMappedAndFilteredListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).toList();
    }

    public static <T, R> Set<R> getMappedAndFilteredSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).collect(Collectors.toSet());
    }
}
