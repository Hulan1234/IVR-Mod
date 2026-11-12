package net.hulan.ivr.render_promotion.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import com.sun.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;
import net.hulan.ivr.client.IVRConfig;

/**
 * Conservative, process-aware budgets shared by the client render caches.
 *
 * <p>Physical memory is queried through optional MXBean methods so this class
 * remains usable on JVMs where those methods are not exposed.  The Runtime
 * heap limit is always used as a fallback and as a hard ceiling.</p>
 */
public final class IVRMemoryBudget {
    private static final long MIN_BUDGET = (long) 1024 * 1024;
    private static final long MAX_CPU_OR_GPU = 64L * 1024 * 1024;
    private static final long SAMPLE_INTERVAL_NANOS = 500_000_000L;
    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final AtomicLong CPU_REJECTS = new AtomicLong();
    private static final AtomicLong GPU_REJECTS = new AtomicLong();
    private static final AtomicLong STAGING_REJECTS = new AtomicLong();
    private static final AtomicLong ASYNC_REJECTS = new AtomicLong();
    private static volatile Snapshot cached;
    private static volatile long sampledAt;

    private IVRMemoryBudget() { }

    public record Snapshot(long physicalBytes, long processBytes, long availableBytes,
                           long cpuMeshBytes, long gpuMeshBytes, long stagingBytes,
                           long asyncQueueBytes, boolean physicalKnown) {
    }

    public static Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot current = cached;
        if (current != null && now - sampledAt < SAMPLE_INTERVAL_NANOS) return current;
        synchronized (IVRMemoryBudget.class) {
            now = System.nanoTime();
            current = cached;
            if (current != null && now - sampledAt < SAMPLE_INTERVAL_NANOS) return current;
            current = sample();
            cached = current;
            sampledAt = now;
            return current;
        }
    }

    private static Snapshot sample() {
        long maxHeap = positive(RUNTIME.maxMemory());
        long process = heapUsed();
        long physicalTotal = physicalTotal();
        long physicalFree = physicalFree();
        boolean physicalKnown = physicalTotal > 0L && physicalFree > 0L;
        long fallbackAvailable = Math.max(0L, maxHeap - process);
        // getFreeMemorySize/getFreePhysicalMemorySize already describe memory
        // available outside this process. Do not subtract process usage again.
        long available = physicalKnown ? physicalFree : fallbackAvailable;
        // Keep a small reserve for Minecraft and the JVM itself.
        long usable = Math.max(MIN_BUDGET * 4L, Math.min(maxHeap, available));
        int configuredMiB = IVRConfig.memoryBudgetMiB;
        long cpu;
        long gpu;
        long staging;
        long async;
        if (configuredMiB > 0) {
            // An explicit value is a hard total for the four IVR pools. Keep
            // the previous relative weights (4:4:1:2) without applying the
            // automatic per-pool minimums, which could exceed the total.
            long total = (long) configuredMiB * MIN_BUDGET;
            long unit = total / 10L;
            cpu = unit * 4L;
            gpu = unit * 4L;
            staging = unit;
            async = total - cpu - gpu - staging;
        } else {
            cpu = clamp(usable / 8L, 4L * MIN_BUDGET, MAX_CPU_OR_GPU);
            gpu = clamp(usable / 8L, 4L * MIN_BUDGET, MAX_CPU_OR_GPU);
            staging = clamp(usable / 32L, MIN_BUDGET, 16L * MIN_BUDGET);
            async = clamp(usable / 16L, 2L * MIN_BUDGET, 32L * MIN_BUDGET);
        }
        return new Snapshot(physicalKnown ? physicalTotal : 0L, process, available,
                cpu, gpu, staging, async, physicalKnown);
    }

    private static long heapUsed() {
        try {
            MemoryUsage heap = MEMORY.getHeapMemoryUsage();
            MemoryUsage nonHeap = MEMORY.getNonHeapMemoryUsage();
            long heapBytes = heap == null ? 0L : Math.max(0L, heap.getUsed());
            long nonHeapBytes = nonHeap == null ? 0L : Math.max(0L, nonHeap.getUsed());
            if (heapBytes > 0L || nonHeapBytes > 0L) return heapBytes + nonHeapBytes;
        } catch (RuntimeException ignored) { }
        return Math.max(0L, RUNTIME.totalMemory() - RUNTIME.freeMemory());
    }

    private static long physicalTotal() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean extended) {
                long value = extended.getTotalPhysicalMemorySize();
                if (value > 0L) return value;
                value = extended.getTotalMemorySize();
                if (value > 0L) return value;
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return 0L;
    }

    private static long physicalFree() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean extended) {
                long value = extended.getFreePhysicalMemorySize();
                if (value > 0L) return value;
                value = extended.getFreeMemorySize();
                if (value > 0L) return value;
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return 0L;
    }

    private static long positive(long value) { return Math.max(1L, value); }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public static long cpuMeshBudgetBytes() { return snapshot().cpuMeshBytes(); }
    public static long gpuMeshBudgetBytes() { return snapshot().gpuMeshBytes(); }
    public static long stagingBufferBudgetBytes() { return snapshot().stagingBytes(); }
    public static long asyncQueueBudgetBytes() { return snapshot().asyncQueueBytes(); }

    public static void recordReject(String kind) {
        switch (kind) {
            case "cpu" -> CPU_REJECTS.incrementAndGet();
            case "gpu" -> GPU_REJECTS.incrementAndGet();
            case "staging" -> STAGING_REJECTS.incrementAndGet();
            case "async" -> ASYNC_REJECTS.incrementAndGet();
            default -> { }
        }
    }

    /** Intended for the F3 overlay; sampling is cached and allocation is isolated here. */
    public static String debugLine() {
        Snapshot s = snapshot();
        return "IVR memory " + format(s.availableBytes()) + " avail / " + format(s.processBytes())
                + " process, cpu " + format(s.cpuMeshBytes()) + ", gpu " + format(s.gpuMeshBytes())
                + ", stage " + format(s.stagingBytes()) + ", async " + format(s.asyncQueueBytes())
                + " rejects c/g/s/a " + CPU_REJECTS.get() + "/" + GPU_REJECTS.get() + "/"
                + STAGING_REJECTS.get() + "/" + ASYNC_REJECTS.get()
                + (s.physicalKnown() ? " physical" : " fallback");
    }

    private static String format(long bytes) {
        if (bytes >= 1024L * 1024L) return (bytes / (1024L * 1024L)) + "M";
        return (bytes / 1024L) + "K";
    }
}
