package net.hulan.ivr.render_promotion.cache;

import mtr.data.TrainClient;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Caches per-car lengths and invalidates them when the client train changes. */
public final class TrainLengthCache {

    private static final Map<TrainClient, Entry> ENTRIES = new WeakHashMap<>();

    public static synchronized void observe(TrainClient train, Vec3[] positions) {
        if (train == null) return;
        double[] lengths = readLengths(train);
        double[] resolved = resolveLengths(train, positions, lengths);
        long signature = 17L;
        signature = 31L * signature + train.trainCars;
        signature = 31L * signature + train.spacing;
        signature = 31L * signature + train.sidingId;
        signature = 31L * signature + System.identityHashCode(train.path);
        for (double length : resolved) {
            signature = 31L * signature + Double.doubleToLongBits(Math.rint(length * 1000D) / 1000D);
        }
        Entry previous = ENTRIES.get(train);
        if (previous != null && previous.signature == signature) return;
        ENTRIES.put(train, new Entry(signature, resolved));
    }

    public static synchronized double length(TrainClient train, int carIndex, int spacing) {
        Entry entry = ENTRIES.get(train);
        if (entry != null && carIndex >= 0 && carIndex < entry.lengths.length) {
            return entry.lengths[carIndex];
        }
        return Math.max(1D, spacing);
    }

    public static synchronized void invalidate(TrainClient train) {
        if (train == null) ENTRIES.clear(); else ENTRIES.remove(train);
    }

    private static double[] resolveLengths(TrainClient train, Vec3[] positions, double[] explicit) {
        int count = Math.max(0, train.trainCars);
        double[] result = new double[count];
        for (int i = 0; i < count; i++) {
            double value = i < explicit.length ? explicit[i] : Double.NaN;
            if (!finitePositive(value) && positions != null && positions.length > 1) {
                double previous = i > 0 ? distance(positions[i - 1], positions[i]) : Double.NaN;
                double next = i + 1 < positions.length ? distance(positions[i], positions[i + 1]) : Double.NaN;
                value = finitePositive(previous) && finitePositive(next) ? Math.min(previous, next)
                        : finitePositive(next) ? next : previous;
            }
            result[i] = finitePositive(value) ? value : Math.max(1D, train.spacing);
        }
        return result;
    }

    private static double[] readLengths(TrainClient train) {
        String[] names = {"carLengths", "trainCarLengths", "lengths", "carLength"};
        for (String name : names) {
            Object value = fieldValue(train, name);
            double[] result = toDoubles(value);
            if (result.length > 0) return result;
        }
        return new double[0];
    }

    private static double[] toDoubles(Object value) {
        if (value instanceof List<?> list) {
            double[] result = new double[list.size()];
            for (int i = 0; i < result.length; i++) {
                Object item = list.get(i);
                if (!(item instanceof Number number)) return new double[0];
                result[i] = number.doubleValue();
            }
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            int size = Array.getLength(value);
            double[] result = new double[size];
            for (int i = 0; i < size; i++) {
                Object item = Array.get(value, i);
                if (!(item instanceof Number number)) return new double[0];
                result[i] = number.doubleValue();
            }
            return result;
        }
        return new double[0];
    }

    private static Object fieldValue(Object object, String name) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Continue through the MTR hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double distance(Vec3 first, Vec3 second) {
        if (first == null || second == null) return Double.NaN;
        return first.distanceTo(second);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.01D;
    }

    private record Entry(long signature, double[] lengths) {
        private Entry {
            lengths = lengths == null ? new double[0] : lengths.clone();
        }
    }
}
