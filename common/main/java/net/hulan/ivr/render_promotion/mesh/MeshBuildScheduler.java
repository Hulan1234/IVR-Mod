package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Future;

/** Bounded pure-data construction. Never runs rejected work on the rendering caller. */
public final class MeshBuildScheduler {
    public record Ticket(VertexMeshKey key, long generation, long entryGeneration) {}
    public record Completed(Ticket ticket, VertexMesh mesh) {}
    private static final int LIMIT = 64;
    private final Map<VertexMeshKey, Ticket> pending = new HashMap<>();
    private final Map<VertexMeshKey, Long> pendingBytes = new HashMap<>();
    private final Map<VertexMeshKey, Future<?>> futures = new HashMap<>();
    private long bytes;
    private static final long BYTE_LIMIT = 8L * 1024 * 1024;
    private final ArrayBlockingQueue<Completed> completed = new ArrayBlockingQueue<>(LIMIT);
    /** Keep vertex construction off the render thread while bounding CPU work. */
    private static final int WORKERS = Math.max(2, Math.min(4,
            Runtime.getRuntime().availableProcessors() - 1));
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(LIMIT), runnable -> {
                Thread thread = new Thread(runnable, "IVR-mesh-builder");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public MeshBuildScheduler() { executor.allowCoreThreadTimeOut(true); }

    /** Input is copied before submission; worker cannot access a world, model or GPU. */
    public synchronized boolean request(Ticket ticket, List<VertexMesh.Vertex> vertices) {
        if (pending.containsKey(ticket.key())) return true;
        long size = 64L + vertices.size() * 48L;
        if (pending.size() >= LIMIT || vertices.size() > 65536 || bytes + size > BYTE_LIMIT) return false;
        List<VertexMesh.Vertex> snapshot = List.copyOf(vertices);
        pending.put(ticket.key(), ticket);
        pendingBytes.put(ticket.key(), size);
        bytes += size;
        FutureTask<Void> task = new FutureTask<>(() -> {
                VertexMesh mesh;
                try { mesh = new VertexMesh(snapshot); }
                catch (RuntimeException failure) { mesh = null; }
                synchronized (this) {
                    if (pending.get(ticket.key()) != ticket) {
                        futures.remove(ticket.key());
                        return null;
                    }
                    if (!completed.offer(new Completed(ticket, mesh))) removeInternal(ticket.key());
                    futures.remove(ticket.key());
                }
                return null;
            });
        futures.put(ticket.key(), task);
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            futures.remove(ticket.key());
            removeInternal(ticket.key());
            return false;
        }
    }
    public synchronized boolean requestQuads(Ticket ticket, List<QuadSpec> specs) {
        if (pending.containsKey(ticket.key())) return true;
        long size = 64L + specs.size() * 4L * 48L;
        if (pending.size() >= LIMIT || specs.size() > 16384 || bytes + size > BYTE_LIMIT) return false;
        List<QuadSpec> snapshot = List.copyOf(specs);
        pending.put(ticket.key(), ticket); pendingBytes.put(ticket.key(), size); bytes += size;
        FutureTask<Void> task = new FutureTask<>(() -> {
                VertexMesh mesh;
                try { mesh = new VertexMesh(snapshot.stream().flatMap(spec -> spec.expand().stream()).toList()); }
                catch (RuntimeException failure) { mesh = null; }
                synchronized (this) {
                    if (pending.get(ticket.key()) != ticket) {
                        futures.remove(ticket.key());
                        return null;
                    }
                    if (!completed.offer(new Completed(ticket, mesh))) removeInternal(ticket.key());
                    futures.remove(ticket.key());
                }
                return null;
            });
        futures.put(ticket.key(), task);
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            futures.remove(ticket.key());
            removeInternal(ticket.key());
            return false;
        }
    }

    public synchronized Completed poll() {
        Completed result = completed.poll();
        if (result != null && pending.get(result.ticket().key()) == result.ticket()) removeInternal(result.ticket().key());
        return result;
    }
    public synchronized void invalidate(VertexMeshKey key) {
        cancel(key);
        completed.removeIf(result -> result.ticket().key().equals(key));
    }
    public synchronized void clear() {
        for (Future<?> future : futures.values()) future.cancel(true);
        futures.clear();
        pending.clear();
        pendingBytes.clear();
        bytes = 0;
        completed.clear();
        executor.getQueue().clear();
    }
    public synchronized boolean isPending(VertexMeshKey key) { return pending.containsKey(key); }
    private void cancel(VertexMeshKey key) {
        Future<?> future = futures.remove(key);
        if (future != null) future.cancel(true);
        removeInternal(key);
    }

    private void removeInternal(VertexMeshKey key) {
        pending.remove(key);
        Long size = pendingBytes.remove(key);
        if (size != null) bytes -= size;
    }
}
