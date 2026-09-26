package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.algo.LanczosSolver;
import com.github.guoyyit.sparseeigensolverjava.algo.LanczosState;
import com.github.guoyyit.sparseeigensolverjava.config.SolverProperties;
import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidator;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import com.github.guoyyit.sparseeigensolverjava.model.EigenPairResult;
import com.github.guoyyit.sparseeigensolverjava.model.EigenRequest;
import com.github.guoyyit.sparseeigensolverjava.model.EigenResponse;
import com.github.guoyyit.sparseeigensolverjava.model.SolveStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates validation, bounded execution, periodic snapshots, restart
 * recovery and deterministic result assembly for eigensolve requests.
 *
 * <p>Jobs are keyed by client-supplied {@code requestId}. Submitting the same
 * requestId while a job is live is rejected; after a process restart the same
 * requestId resumes from the latest snapshot. Completed results are journaled
 * inside the snapshot and returned without recomputation, so they are never
 * emitted twice.
 */
@Service
public class EigenSolverService {

    private static final Logger log = LoggerFactory.getLogger(EigenSolverService.class);

    static final String SIGN_CONVENTION =
            "unit 2-norm; the component with largest magnitude (smallest index on "
                    + "ties) is non-negative";
    static final String NORMALIZATION = "||v||_2 = 1";
    static final String ALGORITHM =
            "Lanczos (full reorthogonalization) + Sturm-bisection eigenvalues + inverse-iteration Ritz vectors";

    private final MatrixValidator matrixValidator;
    private final SolverProperties props;
    private final SnapshotStore snapshotStore;
    private final SolverExecutor executor;
    private final LanczosSolver solver;
    private final AuditService audit;

    private final ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();

    /** In-process crash injection points for restart-recovery tests. */
    private final ConcurrentHashMap<String, Integer> crashAtIteration =
            new ConcurrentHashMap<>();

    private static final class JobRecord {
        volatile EigenResponse response;
        final CountDownLatch done = new CountDownLatch(1);
        volatile long deadlineMs;
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    }

    public EigenSolverService(MatrixValidator matrixValidator, SolverProperties props,
                              SnapshotStore snapshotStore, SolverExecutor executor,
                              LanczosSolver solver, AuditService audit) {
        this.matrixValidator = matrixValidator;
        this.props = props;
        this.snapshotStore = snapshotStore;
        this.executor = executor;
        this.solver = solver;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public EigenResponse submit(EigenRequest req, boolean waitForCompletion) {
        long start = System.currentTimeMillis();
        ValidatedInput input = validate(req, start);

        // Converged results are journaled: identical re-submission returns the
        // committed answer without recomputation and without duplicate emission.
        // Non-converged/timeout failures remain retriable under the same id.
        LanczosState existing = snapshotStore.loadState(input.requestId);
        if (existing != null && existing.finished
                && "CONVERGED".equals(existing.status)) {
            return toResponse(existing, input.requestId, input,
                    SolveStatus.CONVERGED, start);
        }

        JobRecord rec = new JobRecord();
        JobRecord previous = jobs.putIfAbsent(input.requestId, rec);
        if (previous != null) {
            audit.rejected(input.requestId, ErrorCode.JOB_ALREADY_EXISTS.name(),
                    "job already present", input.n, input.nnz, input.estBytes);
            throw new SolverException(ErrorCode.JOB_ALREADY_EXISTS,
                    "request " + input.requestId + " is already registered");
        }

        rec.deadlineMs = start + input.timeLimitMs;
        rec.response = baseResponse(input, SolveStatus.PENDING);

        int inputHash = input.fingerprint;
        if (existing != null) {
            if (existing.finished) {
                // A retriable terminal failure (not converged / timeout): drop the
                // old terminal snapshot and recompute from scratch.
                snapshotStore.clearState(input.requestId);
                existing = null;
            } else {
                ensureSnapshotMatches(existing, input, inputHash);
            }
        }

        final LanczosState resumed = existing;
        try {
            executor.submit(input.requestId, () -> runJob(input, rec, resumed, inputHash));
        } catch (SolverException ex) {
            jobs.remove(input.requestId);
            audit.rejected(input.requestId, ex.getErrorCode().name(), ex.getMessage(),
                    input.n, input.nnz, input.estBytes);
            throw ex;
        }

        if (waitForCompletion) {
            awaitCompletion(rec, input.timeLimitMs + 500L);
        }
        return rec.response;
    }

    public EigenResponse getStatus(String requestId) {
        JobRecord rec = jobs.get(requestId);
        if (rec == null) {
            LanczosState st = snapshotStore.loadState(requestId);
            if (st != null) {
                SolveStatus ss = st.status.equals("CONVERGED") ? SolveStatus.CONVERGED
                        : st.status.equals("CANCELLED") ? SolveStatus.CANCELLED
                        : SolveStatus.FAILED;
                return toResponse(st, requestId, null, ss, 0L);
            }
            throw new SolverException(ErrorCode.JOB_NOT_FOUND,
                    "no job with requestId " + requestId);
        }
        return rec.response;
    }

    public EigenResponse cancel(String requestId) {
        JobRecord rec = jobs.get(requestId);
        boolean removed = executor.cancel(requestId);
        if (rec != null) {
            rec.cancelRequested.set(true);
        }
        if (rec == null && !removed) {
            LanczosState st = snapshotStore.loadState(requestId);
            if (st != null && !st.finished) {
                snapshotStore.clearState(requestId);
                EigenResponse r = new EigenResponse();
                r.requestId = requestId;
                r.status = SolveStatus.CANCELLED;
                r.message = "cancelled; reclaimed orphan snapshot";
                return r;
            }
            throw new SolverException(ErrorCode.JOB_NOT_FOUND,
                    "no job with requestId " + requestId);
        }
        if (rec != null) {
            try {
                rec.done.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            EigenResponse r = rec.response;
            if (r.status != SolveStatus.CONVERGED && r.status != SolveStatus.FAILED) {
                r.status = SolveStatus.CANCELLED;
            }
            return r;
        }
        EigenResponse r = new EigenResponse();
        r.requestId = requestId;
        r.status = SolveStatus.CANCELLED;
        return r;
    }

    /** Whether a live (in-memory, unfinished) job exists for the request. */
    public boolean isJobLive(String requestId) {
        return jobs.containsKey(requestId) || executor.isRunning(requestId);
    }

    /**
     * Test support: abort the in-process run of {@code requestId} immediately
     * after completing iteration {@code iteration}, simulating a process crash.
     * The preceding periodic snapshot stays on disk for resume.
     */
    public void injectCrashAfterIteration(String requestId, int iteration) {
        crashAtIteration.put(requestId, iteration);
    }

    // ------------------------------------------------------------------
    // Job execution
    // ------------------------------------------------------------------

    private void runJob(ValidatedInput input, JobRecord rec, LanczosState resumed,
                        int inputHash) {
        long start = System.currentTimeMillis();
        LanczosState state = resumed;
        try {
            if (resumed == null) {
                state = solver.init(input.requestId, input.matrix, input.seed,
                        input.nev, input.tolerance, input.intervalLower,
                        input.intervalUpper, input.maxIterations);
                state.inputHash = inputHash;
            }
            rec.response = baseResponse(input, SolveStatus.RUNNING);
            rec.response.snapshotIteration = state.j;

            final LanczosState st0 = state;
            LanczosSolver.Control control = new LanczosSolver.Control() {
                private long lastSnapshot = System.currentTimeMillis();

                @Override
                public boolean isCancelled() {
                    return rec.cancelRequested.get()
                            || Thread.currentThread().isInterrupted();
                }

                @Override
                public long deadlineMs() {
                    return rec.deadlineMs;
                }

                @Override
                public void onStepComplete(LanczosState s) {
                    long now = System.currentTimeMillis();
                    if (now - lastSnapshot >= input.snapshotIntervalMs) {
                        snapshotStore.saveState(input.requestId, s);
                        lastSnapshot = now;
                        rec.response.snapshotIteration = s.j;
                    }
                    Integer crashIter = crashAtIteration.get(input.requestId);
                    if (crashIter != null && s.j >= crashIter) {
                        snapshotStore.saveState(input.requestId, s);
                        crashAtIteration.remove(input.requestId);
                        throw new SimulatedCrashException(input.requestId, s.j);
                    }
                }
            };

            solver.run(st0, input.matrix, control);
            finish(st0, input, rec, start);
        } catch (SimulatedCrashException crash) {
            log.warn("simulated crash for {} after iteration {}",
                    crash.requestId, crash.iteration);
            // Simulated process death: durable snapshot survives, live job
            // record is dropped and any synchronous waiter is released.
            EigenResponse snapshotView = rec.response;
            if (snapshotView != null) {
                snapshotView.status = SolveStatus.FAILED;
                snapshotView.errorCode = "PROCESS_CRASHED";
                snapshotView.message = "process terminated at iteration "
                        + crash.iteration + "; resumable from snapshot";
                snapshotView.iterations = crash.iteration;
                snapshotView.snapshotIteration = crash.iteration;
            }
            rec.done.countDown();
            jobs.remove(input.requestId);
        } catch (SolverException ex) {
            log.warn("job {} failed: {}", input.requestId, ex.getMessage());
            failResponse(rec, input, ex.getErrorCode().name(), ex.getMessage(),
                    state, start);
            jobs.remove(input.requestId);
        } catch (Throwable t) {
            log.error("job {} unexpected error", input.requestId, t);
            failResponse(rec, input, ErrorCode.INTERNAL_ERROR.name(),
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    state, start);
            jobs.remove(input.requestId);
        }
    }

    private void finish(LanczosState state, ValidatedInput input, JobRecord rec,
                        long start) {
        boolean converged = state.status.equals("CONVERGED");
        if (converged) {
            snapshotStore.saveState(input.requestId, state);
            rec.response = toResponse(state, input.requestId, input,
                    SolveStatus.CONVERGED, start);
        } else if ("CANCELLED".equals(state.status)) {
            snapshotStore.clearState(input.requestId);
            EigenResponse r = baseResponse(input, SolveStatus.CANCELLED);
            r.iterations = state.j;
            r.message = state.message;
            rec.response = r;
        } else {
            String code = state.errorCode != null ? state.errorCode
                    : ErrorCode.NOT_CONVERGED.name();
            snapshotStore.saveState(input.requestId, state);
            EigenResponse r = baseResponse(input, SolveStatus.FAILED);
            r.errorCode = code;
            r.message = state.message;
            r.iterations = state.j;
            r.snapshotIteration = state.j;
            r.results = diagnostics(state);
            rec.response = r;
        }
        long elapsed = System.currentTimeMillis() - start;
        audit.finished(input.requestId, state.status, input.n, input.nnz, elapsed);
        rec.done.countDown();
        jobs.remove(input.requestId);
    }

    private void failResponse(JobRecord rec, ValidatedInput input, String code,
                              String message, LanczosState state, long start) {
        EigenResponse r = baseResponse(input, SolveStatus.FAILED);
        r.errorCode = code;
        r.message = message;
        if (state != null) {
            r.iterations = state.j;
            r.results = diagnostics(state);
        }
        rec.response = r;
        audit.finished(input.requestId, "FAILED:" + code, input.n, input.nnz,
                System.currentTimeMillis() - start);
        rec.done.countDown();
        jobs.remove(input.requestId);
    }

    // ------------------------------------------------------------------
    // Validation / limits
    // ------------------------------------------------------------------

    private static final class ValidatedInput {
        String requestId;
        int n;
        int nnz;
        long estBytes;
        CsrMatrix matrix;
        int nev;
        double tolerance;
        int maxIterations;
        Double intervalLower;
        Double intervalUpper;
        long seed;
        long timeLimitMs;
        long snapshotIntervalMs;
        int fingerprint;
    }

    private ValidatedInput validate(EigenRequest req, long start) {
        if (req == null) {
            throw new SolverException(ErrorCode.INVALID_CONFIG, "request body is empty");
        }
        ValidatedInput v = new ValidatedInput();
        v.requestId = req.requestId;
        if (v.requestId == null || !v.requestId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new SolverException(ErrorCode.INVALID_CONFIG,
                    "requestId must match [A-Za-z0-9._-]{1,128}");
        }
        v.n = req.dimension;
        if (v.n <= 0) {
            throw new SolverException(ErrorCode.INVALID_DIMENSION,
                    "dimension must be positive, got " + v.n);
        }
        if (props.getMaxDimension() > 0 && v.n > props.getMaxDimension()) {
            audit.rejected(v.requestId, ErrorCode.MATRIX_TOO_LARGE.name(),
                    "n=" + v.n + " exceeds limit " + props.getMaxDimension(),
                    v.n, lengthOf(req.colIndex), 0L);
            throw new SolverException(ErrorCode.MATRIX_TOO_LARGE,
                    "dimension " + v.n + " exceeds configured limit "
                            + props.getMaxDimension());
        }

        int nnz = lengthOf(req.colIndex);
        long estBytes = 16L + (long) lengthOf(req.rowPtr) * 4L
                + (long) nnz * 12L + (long) v.n * 8L * 3L;
        v.nnz = nnz;
        v.estBytes = estBytes;
        if (props.getMaxNonZeros() > 0 && nnz > props.getMaxNonZeros()) {
            audit.rejected(v.requestId, ErrorCode.MATRIX_TOO_LARGE.name(),
                    "nnz=" + nnz + " exceeds " + props.getMaxNonZeros(), v.n, nnz, estBytes);
            throw new SolverException(ErrorCode.MATRIX_TOO_LARGE,
                    "non-zero count " + nnz + " exceeds configured limit "
                            + props.getMaxNonZeros());
        }
        if (props.getMaxRequestBytes() > 0 && estBytes > props.getMaxRequestBytes()) {
            audit.rejected(v.requestId, ErrorCode.MATRIX_TOO_LARGE.name(),
                    "estBytes=" + estBytes, v.n, nnz, estBytes);
            throw new SolverException(ErrorCode.MATRIX_TOO_LARGE,
                    "estimated request footprint " + estBytes
                            + " bytes exceeds limit " + props.getMaxRequestBytes());
        }

        v.nev = req.nev != null ? req.nev : 1;
        if (v.nev < 1 || v.nev > v.n) {
            throw new SolverException(ErrorCode.INVALID_NEV,
                    "nev must be in [1,n=" + v.n + "], got " + v.nev);
        }
        v.tolerance = req.tolerance != null ? req.tolerance : 1.0e-8;
        if (!Double.isFinite(v.tolerance) || v.tolerance <= 0.0
                || v.tolerance >= 1.0) {
            throw new SolverException(ErrorCode.INVALID_TOLERANCE,
                    "tolerance must be a finite value in (0,1), got "
                            + req.tolerance);
        }
        int cap = Math.min(props.getMaxIterations(), v.n);
        v.maxIterations = req.maxIterations != null ? req.maxIterations
                : Math.min(props.getDefaultMaxIterations(), v.n);
        if (v.maxIterations < v.nev || v.maxIterations > v.n
                || v.maxIterations > props.getMaxIterations()) {
            throw new SolverException(ErrorCode.INVALID_MAX_ITERATIONS,
                    "maxIterations must be in [nev," + cap + "], got "
                            + v.maxIterations);
        }
        v.intervalLower = req.intervalLower;
        v.intervalUpper = req.intervalUpper;
        if (v.intervalLower != null && !Double.isFinite(v.intervalLower)) {
            throw new SolverException(ErrorCode.INVALID_INTERVAL,
                    "intervalLower must be finite");
        }
        if (v.intervalUpper != null && !Double.isFinite(v.intervalUpper)) {
            throw new SolverException(ErrorCode.INVALID_INTERVAL,
                    "intervalUpper must be finite");
        }
        if (v.intervalLower != null && v.intervalUpper != null
                && v.intervalLower > v.intervalUpper) {
            throw new SolverException(ErrorCode.INVALID_INTERVAL,
                    "intervalLower (" + v.intervalLower + ") must be <= intervalUpper ("
                            + v.intervalUpper + ")");
        }
        v.seed = req.seed != null ? req.seed : 1L;
        v.timeLimitMs = req.timeLimitMs != null ? req.timeLimitMs
                : props.getDefaultTimeLimitMs();
        if (v.timeLimitMs <= 0 || v.timeLimitMs > props.getMaxTimeLimitMs()) {
            throw new SolverException(ErrorCode.INVALID_CONFIG,
                    "timeLimitMs must be in (0," + props.getMaxTimeLimitMs() + "], got "
                            + v.timeLimitMs);
        }
        v.snapshotIntervalMs = req.snapshotIntervalMs != null
                ? req.snapshotIntervalMs : props.getSnapshotIntervalMs();
        if (v.snapshotIntervalMs <= 0) {
            throw new SolverException(ErrorCode.INVALID_CONFIG,
                    "snapshotIntervalMs must be positive");
        }

        long basisBytes = (long) (v.maxIterations + 1) * v.n * 8L
                + 2L * v.maxIterations * 8L;
        if (props.getPerRequestMemoryBytes() > 0
                && basisBytes > props.getPerRequestMemoryBytes()) {
            audit.rejected(v.requestId, ErrorCode.RESOURCE_LIMIT.name(),
                    "basisBytes=" + basisBytes, v.n, nnz, estBytes);
            throw new SolverException(ErrorCode.RESOURCE_LIMIT,
                    "requested Krylov basis would require " + basisBytes
                            + " bytes, exceeding the per-request limit "
                            + props.getPerRequestMemoryBytes()
                            + "; reduce maxIterations");
        }

        v.matrix = matrixValidator.validate(v.n, req.rowPtr, req.colIndex,
                req.values, props.getMaxNonZeros());
        v.fingerprint = fingerprint(v.matrix, v.nev, v.tolerance,
                v.intervalLower, v.intervalUpper, v.seed);

        audit.accepted(v.requestId, v.n, nnz, estBytes);
        return v;
    }

    private void ensureSnapshotMatches(LanczosState st, ValidatedInput input,
                                       int inputHash) {
        if (st.inputHash != inputHash || st.seed != input.seed
                || st.n != input.n || st.nev != input.nev
                || st.tolerance != input.tolerance
                || !eq(st.intervalLower, input.intervalLower)
                || !eq(st.intervalUpper, input.intervalUpper)) {
            throw new SolverException(ErrorCode.SNAPSHOT_MISMATCH,
                    "snapshot for " + input.requestId
                            + " was produced from different input/parameters");
        }
    }

    private static boolean eq(Double a, Double b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int lengthOf(Object[] a) {
        return a == null ? 0 : a.length;
    }

    private static int lengthOf(int[] a) {
        return a == null ? 0 : a.length;
    }

    private static int fingerprint(CsrMatrix a, int nev, double tol, Double lo,
                                   Double hi, long seed0) {
        int result = a.getN();
        result = 31 * result + java.util.Arrays.hashCode(a.getRowPtr());
        result = 31 * result + java.util.Arrays.hashCode(a.getColIndex());
        result = 31 * result + java.util.Arrays.hashCode(a.getValues());
        result = 31 * result + nev;
        result = 31 * result + Double.hashCode(tol);
        result = 31 * result + (lo == null ? 0 : Double.hashCode(lo));
        result = 31 * result + (hi == null ? 0 : Double.hashCode(hi));
        result = 31 * result + Long.hashCode(seed0);
        return result;
    }

    // ------------------------------------------------------------------
    // Response mapping
    // ------------------------------------------------------------------

    private static EigenResponse baseResponse(ValidatedInput v, SolveStatus status) {
        EigenResponse r = new EigenResponse();
        r.requestId = v.requestId;
        r.status = status;
        r.requestedNev = v.nev;
        r.intervalLower = v.intervalLower;
        r.intervalUpper = v.intervalUpper;
        r.seed = v.seed;
        r.signConvention = SIGN_CONVENTION;
        r.normalization = NORMALIZATION;
        r.algorithm = ALGORITHM;
        r.results = new ArrayList<>();
        return r;
    }

    private EigenResponse toResponse(LanczosState st, String requestId,
                                     ValidatedInput input, SolveStatus status,
                                     long start) {
        EigenResponse r = new EigenResponse();
        r.requestId = requestId;
        r.status = status;
        r.iterations = st.j;
        r.snapshotIteration = st.j;
        r.seed = st.seed;
        r.requestedNev = st.nev;
        r.intervalLower = st.intervalLower;
        r.intervalUpper = st.intervalUpper;
        r.signConvention = SIGN_CONVENTION;
        r.normalization = NORMALIZATION;
        r.algorithm = ALGORITHM;
        r.errorCode = st.errorCode;
        r.message = st.message;
        r.results = "CONVERGED".equals(st.status)
                ? solver.assembleResults(st) : diagnostics(st);
        if (input != null) {
            r.elapsedMs = Math.max(0L, System.currentTimeMillis() - start);
        }
        return r;
    }

    private static List<EigenPairResult> diagnostics(LanczosState st) {
        List<EigenPairResult> out = new ArrayList<>();
        if (st.lastRitzValues == null) {
            return out;
        }
        for (int i = 0; i < st.lastRitzValues.length; i++) {
            EigenPairResult p = new EigenPairResult();
            p.eigenvalue = st.lastRitzValues[i];
            p.residual = st.lastResidualEstimates[i];
            p.converged = false;
            out.add(p);
        }
        return out;
    }

    private void awaitCompletion(JobRecord rec, long waitMs) {
        try {
            rec.done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown inside a worker to emulate an abrupt process termination. */
    private static final class SimulatedCrashException extends RuntimeException {
        final String requestId;
        final int iteration;

        SimulatedCrashException(String requestId, int iteration) {
            super("simulated crash at iteration " + iteration);
            this.requestId = requestId;
            this.iteration = iteration;
        }
    }
}
