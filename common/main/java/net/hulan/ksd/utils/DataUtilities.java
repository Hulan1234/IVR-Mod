package net.hulan.ksd.utils;

import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DataUtilities {

    public static KSDStation getStation(Set<KSDStation> stations, long stationId) {
        return getOrNull(stations, s -> s.id == stationId);
    }

    public static KSDRoute getRoute(Set<KSDRoute> routes, long routeId) {
        return getOrNull(routes, r -> r.id == routeId);
    }

    public static KSDPlatform getPlatform(Set<KSDPlatform> platforms, long platformId) {
        return getOrNull(platforms, p -> p.id == platformId);
    }

    public static <T> T getOrNull(Collection<T> dataCollection, Predicate<T> filter) {
        return getOrDefault(dataCollection, filter, () -> null);
    }

    public static <T> T getOrDefault(Collection<T> dataCollection, Predicate<T> filter, Supplier<T> supplier) {
        return dataCollection.stream().filter(filter).findFirst().orElse(supplier.get());
    }

    public static <T> T getOrPut(Collection<T> dataCollection, Predicate<T> filter, Supplier<T> supplier) {
        T value = getOrNull(dataCollection, filter);
        if (value == null) {
            value = supplier.get();
            dataCollection.add(value);
        }
        return value;
    }

    public static <T> void execute(Collection<T> dataCollection, Predicate<T> filter, Consumer<T> action) {
        dataCollection.stream().filter(filter).findFirst().ifPresent(action);
    }

    public static <T> List<T> filterToList(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).toList();
    }

    public static <T> Set<T> getNonNullSetFromDataCollection(Collection<T> dataCollection) {
        return dataCollection.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public static <T> Set<T> filterToSet(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).collect(Collectors.toSet());
    }

    public static <T, R> List<R> sortAndMapToList(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().sorted().map(mapper).toList();
    }

    public static <T, R> List<R> mapToList(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).toList();
    }

    public static <T, R> Set<R> mapToSet(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).collect(Collectors.toSet());
    }

    public static <T, R> List<R> mapAndNonNullToList(Collection<T> dataCollection, Function<T, R> mapper) {
        return mapAndFilterToList(dataCollection, mapper, Objects::nonNull);
    }

    public static <T, R> Set<R> getMappedAndNonNullSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return mapAndFilterToSet(dataCollection, mapper, Objects::nonNull);
    }

    public static <T, R> List<R> mapAndFilterToList(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).toList();
    }

    public static <T, R> Set<R> mapAndFilterToSet(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).collect(Collectors.toSet());
    }

    public static <T> void sortPriorityQueue(PriorityQueue<T> queue) {
        Comparator<? super T> comparator = queue.comparator();
        List<T> list = queue.stream().sorted(comparator).toList();
        queue.clear();
        queue.addAll(list);
    }

    public static <T> List<Integer> getAllIndexFromList(List<T> dataCollection, T value) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < dataCollection.size(); i++) {
            if (dataCollection.get(i).equals(value)) {
                indexes.add(i);
            }
        }
        return indexes;
    }
}
