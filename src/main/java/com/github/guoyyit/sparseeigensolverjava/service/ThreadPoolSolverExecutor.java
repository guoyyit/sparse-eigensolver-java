package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.config.SolverProperties;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded executor for solve jobs.
 *
 * <p>A fixed pool caps concurrently iterating requests at
 * {@code solver.max-concurrency}; additional jobs wait in a bounded queue
 * ({@code solver.queue-capacity}). Cancellation interrupts the worker (or
 * removes a still-queued task) and clears the registry entry, so no thread or
 * partial result is left behind and other requests keep progressing.
 */
public class ThreadPoolSolverExecutor implements SolverExecutor {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolSolverExecutor.class);

    private final SolverProperties props;
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<String, Entry> registry = new ConcurrentHashMap<>();
    private final AtomicLong threadSeq = new AtomicLong();

    private static final class Entry {
        final FutureTask<?> future;
        final AtomicBoolean done = new AtomicBoolean(false);

        Entry(FutureTask<?> future) {
            this.future = future;
        }
    }

    public ThreadPoolSolverExecutor(SolverProperties props) {
        this.props = props;
        int parallelism = Math.max(1, props.getMaxConcurrency());
        this.pool = new ThreadPoolExecutor(
                parallelism, parallelism, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, props.getQueueCapacity())),
                r -> {
                    Thread t = new Thread(r,
                            "eigensolver-" + threadSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Future<?> submit(String requestId, Runnable task) {
        if (registry.containsKey(requestId)) {
            throw new SolverException(ErrorCode.JOB_ALREADY_EXISTS,
                    "a job with requestId " + requestId + " is already registered");
        }
        Entry[] holder = new Entry[1];
        FutureTask<?> ft = new FutureTask<>(() -> {
            try {
                task.run();
            } finally {
                Entry e = holder[0];
                if (e != null && e.done.compareAndSet(false, true)) {
                    registry.remove(requestId, e);
                }
            }
        }, null);
        Entry entry = new Entry(ft);
        holder[0] = entry;
        Entry existing = registry.putIfAbsent(requestId, entry);
        if (existing != null) {
            throw new SolverException(ErrorCode.JOB_ALREADY_EXISTS,
                    "a job with requestId " + requestId + " is already registered");
        }
        try {
            pool.execute(ft);
        } catch (RejectedExecutionException ex) {
            registry.remove(requestId, entry);
            throw new SolverException(ErrorCode.RESOURCE_LIMIT,
                    "server busy: concurrency/queue limit reached, retry later", ex);
        }
        return ft;
    }

    @Override
    public boolean cancel(String requestId) {
        Entry e = registry.get(requestId);
        if (e == null) {
            return false;
        }
        // Remove first if the task is still queued; interrupt if running.
        pool.remove(e.future);
        e.future.cancel(true);
        if (e.done.compareAndSet(false, true)) {
            registry.remove(requestId, e);
        }
        return true;
    }

    @Override
    public boolean isRunning(String requestId) {
        return registry.containsKey(requestId);
    }

    /** Number of requests currently executing a worker thread. */
    public int activeCount() {
        return pool.getActiveCount();
    }

    /** Stops the pool (test helper). */
    public void shutdown() {
        pool.shutdownNow();
    }
}
