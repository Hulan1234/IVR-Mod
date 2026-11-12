package net.hulan.ivr.render_promotion.mesh;

import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.memory.IVRMemoryBudget;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Future;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 有界地构建纯数据；被拒绝的任务不会在渲染线程调用方中执行。 */
public final class MeshBuildScheduler {
    private static final Logger LOGGER = LogManager.getLogger("ivr");
    public record Ticket(VertexMeshKey key, long generation, long entryGeneration) {}
    public record Completed(Ticket ticket, VertexMesh mesh) {}
    private static final int LIMIT = 64;
    private final Map<VertexMeshKey, Ticket> pending = new HashMap<>();
    private final Map<VertexMeshKey, Long> pendingBytes = new HashMap<>();
    private final Map<VertexMeshKey, Future<?>> futures = new HashMap<>();
    private long bytes;
    private static final long BYTE_LIMIT_CEILING = 8L * 1024 * 1024;
    private final ArrayBlockingQueue<Completed> completed = new ArrayBlockingQueue<>(LIMIT);
    private final ArrayDeque<Completed> overflow = new ArrayDeque<>(LIMIT);
    /** 在限制 CPU 工作量的同时，将顶点构造移出渲染线程。 */
    private static final int WORKERS = Math.max(2, Math.min(4,
            Runtime.getRuntime().availableProcessors() - 1));
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(LIMIT), runnable -> {
                Thread thread = new Thread(runnable, "IVR-mesh-builder");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public MeshBuildScheduler() { executor.allowCoreThreadTimeOut(true); }

    /** 提交前复制输入；工作线程无法访问世界、模型或 GPU。 */
    public synchronized boolean request(Ticket ticket, List<VertexMesh.Vertex> vertices) {
        if (pending.containsKey(ticket.key())) return true;
        long size = 64L + vertices.size() * 48L;
        long byteLimit = Math.min(BYTE_LIMIT_CEILING, IVRMemoryBudget.asyncQueueBudgetBytes());
        if (pending.size() >= LIMIT || vertices.size() > 65536 || bytes + size > byteLimit) {
            IVRMemoryBudget.recordReject("async");
            return false;
        }
        List<VertexMesh.Vertex> snapshot = List.copyOf(vertices);
        pending.put(ticket.key(), ticket);
        pendingBytes.put(ticket.key(), size);
        bytes += size;
        FutureTask<Void> task = createWorkerTask(() -> {
                VertexMesh mesh;
                try { mesh = new VertexMesh(snapshot); }
                catch (RuntimeException failure) { mesh = null; }
                catch (Error fatal) { fatalFailure(ticket); throw fatal; }
                try {
                    publishCompletion(ticket, mesh);
                } catch (RuntimeException failure) {
                    publicationFailure(ticket, failure);
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
        long byteLimit = Math.min(BYTE_LIMIT_CEILING, IVRMemoryBudget.asyncQueueBudgetBytes());
        if (pending.size() >= LIMIT || specs.size() > 16384 || bytes + size > byteLimit) {
            IVRMemoryBudget.recordReject("async");
            return false;
        }
        List<QuadSpec> snapshot = List.copyOf(specs);
        pending.put(ticket.key(), ticket); pendingBytes.put(ticket.key(), size); bytes += size;
        FutureTask<Void> task = createWorkerTask(() -> {
                VertexMesh mesh;
                try { mesh = new VertexMesh(snapshot.stream().flatMap(spec -> spec.expand().stream()).toList()); }
                catch (RuntimeException failure) { mesh = null; }
                catch (Error fatal) { fatalFailure(ticket); throw fatal; }
                try {
                    publishCompletion(ticket, mesh);
                } catch (RuntimeException failure) {
                    publicationFailure(ticket, failure);
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
        while (completed.remainingCapacity() > 0 && !overflow.isEmpty()) completed.offer(overflow.removeFirst());
        Completed result = completed.poll();
        if (result != null && pending.get(result.ticket().key()) == result.ticket()) removeInternal(result.ticket().key());
        return result;
    }
    public synchronized void invalidate(VertexMeshKey key) {
        cancel(key);
        completed.removeIf(result -> result.ticket().key().equals(key));
        overflow.removeIf(result -> result.ticket().key().equals(key));
    }
    public synchronized void clear() {
        for (Future<?> future : futures.values()) future.cancel(true);
        futures.clear();
        pending.clear();
        pendingBytes.clear();
        bytes = 0;
        completed.clear();
        overflow.clear();
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

    private void enqueueCompleted(Completed result) {
        if (completed.offer(result)) return;
        if (overflow.size() < LIMIT) {
            overflow.addLast(result);
            return;
        }
        Completed evicted = overflow.removeFirst();
        removeInternal(evicted.ticket().key());
        overflow.addLast(result);
    }

    private synchronized void publishCompletion(Ticket ticket, VertexMesh mesh) {
        if (pending.get(ticket.key()) != ticket) {
            futures.remove(ticket.key());
            return;
        }
        try {
            enqueueCompleted(new Completed(ticket, mesh));
        } catch (Error fatal) {
            removeInternal(ticket.key());
            throw fatal;
        } finally {
            futures.remove(ticket.key());
        }
    }

    private synchronized void fatalFailure(Ticket ticket) {
        if (pending.get(ticket.key()) == ticket) removeInternal(ticket.key());
        futures.remove(ticket.key());
    }

    /** 非致命的发布失败不能让键滞留在待处理映射中。 */
    private synchronized void publicationFailure(Ticket ticket, RuntimeException failure) {
        if (pending.get(ticket.key()) == ticket) removeInternal(ticket.key());
        futures.remove(ticket.key());
        LOGGER.debug("Unable to publish IVR vertex mesh; it will be retried", failure);
    }

    private static FutureTask<Void> createWorkerTask(Callable<Void> callable) {
        return new FutureTask<>(callable) {
            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof Error fatal) {
                        LOGGER.error("Fatal error while building IVR vertex mesh", fatal);
                        throw fatal;
                    }
                    LOGGER.error("Unexpected failure while building IVR vertex mesh", cause);
                }
            }
        };
    }
}
