package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.config.SolverProperties;
import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.EigenResult;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.LanczosSolver;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidationException;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidator;
import com.github.guoyyit.sparseeigensolverjava.core.RequestRejectedException;
import com.github.guoyyit.sparseeigensolverjava.core.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;
import com.github.guoyyit.sparseeigensolverjava.snapshot.SnapshotStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the bounded executor, per-task resource limits, cancellation and
 * snapshot-based recovery of interrupted tasks.
 *
 * <p>Admission control: matrix dimension, nonzero count and an estimated
 * memory footprint are checked against configured limits; at most
 * {@code maxConcurrentTasks} tasks run at once, further submissions are
 * rejected with {@link ErrorCode#OVERLOADED} instead of queueing
 * unboundedly. Every admission/rejection/termination is audit-logged.</p>
 *
 * <p>Cancellation and timeout are cooperative: the solver checks the flag /
 * deadline at every inner step, so threads are reclaimed promptly and no
 * partial result is ever published. Terminal tasks persist a tombstone so a
 * restarted process neither resumes them nor re-emits their results.</p>
 */
@Service
public class EigenTaskManager {

    private static final Logger log = LoggerFactory.getLogger(EigenTaskManager.class);
    /** Retained finished-task records (older ones are evicted lazily). */
    private static final int MAX_RETAINED_RECORDS = 10_000;

    /** Immutable view of one task for polling. */
    public record TaskView(String taskId, SolveStatus status, EigenResult result) {
    }

    private static final class TaskRecord {
        final String id;
        final long submittedAt = System.currentTimeMillis();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        volatile SolveStatus status = SolveStatus.PENDING;
        volatile EigenResult result;
        volatile long deadlineMillis;
        volatile long estimatedBytes;

        TaskRecord(String id) {
            this.id = id;
        }
    }

    private final SolverProperties props;
    private final AuditLogger audit;
    private final SnapshotStore snapshotStore;
    private final ExecutorService executor;
    private final Semaphore permits;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    public EigenTaskManager(SolverProperties props, AuditLogger audit) {
        this.props = props;
        this.audit = audit;
        this.snapshotStore = new SnapshotStore(Path.of(props.getSnapshotDir()));
        this.executor = Executors.newFixedThreadPool(props.getMaxConcurrentTasks(), r -> {
            Thread t = new Thread(r, "eigen-worker");
            t.setDaemon(true);
            return t;
        });
        this.permits = new Semaphore(props.getMaxConcurrentTasks());
    }

    // ------------------------------------------------------------- admission

    /** Submit a new task; returns its id. Rejects invalid or over-limit input. */
    public String submit(CsrMatrix matrix, SolverOptions options) {
        String taskId = UUID.randomUUID().toString();
        // 1) Structural / numeric validation (distinct error codes per defect).
        MatrixValidator.validate(matrix, options);
        // 2) Size ceilings.
        if (matrix.n() > props.getMaxDimension()) {
            audit.rejected(taskId, ErrorCode.INPUT_TOO_LARGE.name(),
                    "dimension " + matrix.n() + " > limit " + props.getMaxDimension());
            throw new RequestRejectedException(ErrorCode.INPUT_TOO_LARGE,
                    "dimension " + matrix.n() + " exceeds limit " + props.getMaxDimension());
        }
        if (matrix.nnz() > props.getMaxNnz()) {
            audit.rejected(taskId, ErrorCode.INPUT_TOO_LARGE.name(),
                    "nnz " + matrix.nnz() + " > limit " + props.getMaxNnz());
            throw new RequestRejectedException(ErrorCode.INPUT_TOO_LARGE,
                    "nonzero count " + matrix.nnz() + " exceeds limit " + props.getMaxNnz());
        }
        // 3) Memory budget from a deterministic estimate.
        long estimate = estimateMemoryBytes(matrix, options);
        if (estimate > props.getMaxMemoryBytes()) {
            audit.rejected(taskId, ErrorCode.INPUT_TOO_LARGE.name(),
                    "estimated " + estimate + " bytes > budget " + props.getMaxMemoryBytes());
            throw new RequestRejectedException(ErrorCode.INPUT_TOO_LARGE,
                    "estimated memory " + estimate + " bytes exceeds per-task budget "
                            + props.getMaxMemoryBytes());
        }
        // 4) Capacity: no unbounded queue.
        if (!permits.tryAcquire()) {
            audit.rejected(taskId, ErrorCode.OVERLOADED.name(), "no free worker slot");
            throw new RequestRejectedException(ErrorCode.OVERLOADED,
                    "service at capacity (" + props.getMaxConcurrentTasks() + " tasks); retry later");
        }
        try {
            // Persist the request first so a crash at any point is recoverable.
            snapshotStore.saveRequest(taskId, matrix, options);
            TaskRecord rec = new TaskRecord(taskId);
            rec.deadlineMillis = props.getMaxWallMillis() > 0
                    ? System.currentTimeMillis() + props.getMaxWallMillis() : 0;
            rec.estimatedBytes = estimate;
            tasks.put(taskId, rec);
            audit.admitted(taskId, matrix.n(), matrix.nnz());
            executor.submit(() -> runTask(rec, matrix, options, false));
            return taskId;
        } catch (RuntimeException e) {
            permits.release();
            tasks.remove(taskId);
            throw e;
        }
    }

    /** Submit and block until the task terminates; used by the sync endpoint. */
    public EigenResult solveSync(CsrMatrix matrix, SolverOptions options) {
        String taskId = submit(matrix, options);
        TaskRecord rec = tasks.get(taskId);
        long wait = props.getMaxWallMillis() > 0 ? props.getMaxWallMillis() + 30_000 : 0;
        long deadline = wait > 0 ? System.currentTimeMillis() + wait : Long.MAX_VALUE;
        while (rec.result == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        EigenResult r = rec.result;
        if (r == null) {
            return EigenResult.failure(SolveStatus.TIMEOUT, ErrorCode.TIMEOUT,
                    "synchronous wait exceeded the task budget", java.util.List.of(), 0, wait);
        }
        return r;
    }

    // --------------------------------------------------------------- polling

    /** Current view of a task; empty if the id is unknown. */
    public Optional<TaskView> poll(String taskId) {
        TaskRecord rec = tasks.get(taskId);
        if (rec == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskView(rec.id, rec.status, rec.result));
    }

    /**
     * Request cancellation. The worker observes the flag at the next inner
     * step, terminates with CANCELLED and releases its slot; no partial
     * result is published.
     */
    public boolean cancel(String taskId) {
        TaskRecord rec = tasks.get(taskId);
        if (rec == null || rec.result != null) {
            return false;
        }
        rec.cancelRequested.set(true);
        audit.cancelled(taskId);
        return true;
    }

    // --------------------------------------------------------------- recovery

    /**
     * Resume tasks that left a request + snapshot behind after a restart.
     * Called once at startup; recovered tasks continue from their latest
     * snapshot and produce results identical to an uninterrupted run.
     */
    public void recoverInterruptedTasks() {
        for (String taskId : snapshotStore.resumableTaskIds()) {
            try {
                Optional<SnapshotStore.StoredRequest> req = snapshotStore.loadRequest(taskId);
                if (req.isEmpty()) {
                    continue;
                }
                permits.acquire();
                TaskRecord rec = new TaskRecord(taskId);
                rec.deadlineMillis = props.getMaxWallMillis() > 0
                        ? System.currentTimeMillis() + props.getMaxWallMillis() : 0;
                tasks.put(taskId, rec);
                audit.recovered(taskId);
                executor.submit(() -> runTask(rec, req.get().matrix(), req.get().options(), true));
                log.info("recovered interrupted task {}", taskId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("cannot recover task {}: {}", taskId, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ run

    private void runTask(TaskRecord rec, CsrMatrix matrix, SolverOptions options, boolean resume) {
        rec.status = SolveStatus.RUNNING;
        long t0 = System.currentTimeMillis();
        try {
            LanczosSolver solver = new LanczosSolver(matrix, options,
                    rec.cancelRequested::get, rec.deadlineMillis);
            EigenResult result = resume
                    ? solver.resume(snapshotStore, rec.id)
                    : solver.solve(snapshotStore, rec.id);
            rec.result = result;
            rec.status = result.status();
        } catch (RuntimeException e) {
            log.error("task {} failed unexpectedly", rec.id, e);
            rec.result = EigenResult.failure(SolveStatus.FAILED, ErrorCode.NUMERICAL_DEGENERACY,
                    "internal solver error: " + e.getMessage(), java.util.List.of(), 0,
                    System.currentTimeMillis() - t0);
            rec.status = SolveStatus.FAILED;
        } finally {
            // Terminal cleanup: tombstone + drop snapshots; free the slot.
            try {
                snapshotStore.finalizeTask(rec.id);
            } catch (RuntimeException e) {
                log.warn("snapshot cleanup failed for {}: {}", rec.id, e.getMessage());
            }
            audit.finished(rec.id, rec.status.name(),
                    System.currentTimeMillis() - t0, rec.estimatedBytes);
            permits.release();
            evictOldRecords();
        }
    }

    /**
     * Deterministic upper-bound estimate of the task's working set:
     * CSR storage + Krylov basis + locked vectors + projected matrix.
     */
    static long estimateMemoryBytes(CsrMatrix m, SolverOptions o) {
        long n = m.n();
        long nnz = m.nnz();
        long maxBasis = Math.min(n, Math.max(2L * o.numEigenvalues() + 8, o.numEigenvalues() + 16L));
        long csrBytes = 12 * nnz + 4 * (n + 1);
        long basisBytes = 8 * n * maxBasis;
        long lockedBytes = 8 * n * o.numEigenvalues();
        long projectedBytes = 8 * maxBasis * maxBasis;
        long workBytes = 8 * n * 8; // SpMV output, Ritz vectors, temporaries
        return csrBytes + basisBytes + lockedBytes + projectedBytes + workBytes;
    }

    private void evictOldRecords() {
        if (tasks.size() <= MAX_RETAINED_RECORDS) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 3_600_000;
        tasks.values().removeIf(rec -> rec.result != null && rec.submittedAt < cutoff);
    }

    @PreDestroy
    public void shutdown() {
        // Snapshots of running tasks are on disk; a restart resumes them.
        tasks.values().forEach(rec -> rec.cancelRequested.set(true));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
