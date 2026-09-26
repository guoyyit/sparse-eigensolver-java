package com.github.guoyyit.sparseeigensolverjava.core;

import com.github.guoyyit.sparseeigensolverjava.snapshot.SnapshotStore;
import com.github.guoyyit.sparseeigensolverjava.snapshot.SolverSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Thick-restart Lanczos solver (restartable Arnoldi form with full
 * reorthogonalization, mathematically equivalent to restarted Lanczos for
 * symmetric A) for the algebraically smallest eigenpairs.
 *
 * <p>Properties:</p>
 * <ul>
 *   <li>Never densifies the input: O(nnz) SpMV + O(n*m^2) dense work with
 *       m = subspace dimension &lt;&lt; n.</li>
 *   <li>Deterministic: the starting vector derives from the configured seed,
 *       all sweeps run in fixed order, eigenvector signs are fixed
 *       (largest-magnitude component positive, first max wins ties).</li>
 *   <li>Converged pairs are locked (committed) and deflated; snapshots carry
 *       them so a resumed run neither recomputes nor re-emits them.</li>
 *   <li>Non-convergence and numerical degeneracy are reported as failures
 *       with the iteration history retained.</li>
 * </ul>
 */
public final class LanczosSolver {

    /** Relative residual threshold below which a Krylov direction is "breakdown". */
    private static final double BREAKDOWN_REL = 1e-13;
    /** Salt mixing the restart counter into deterministic fresh-start vectors. */
    private static final long RESTART_SALT = 0x9E3779B97F4A7C15L;

    private enum ExpandOutcome {OK, BREAKDOWN, DEGENERATE}

    private final CsrMatrix matrix;
    private final SolverOptions options;
    private final BooleanSupplier cancelled;
    /** Absolute wall-clock deadline in millis; 0 disables the timeout. */
    private final long deadlineMillis;
    private final int maxBasis;

    // ---- mutable iteration state (mirrored in SolverSnapshot) ----
    private final List<double[]> basis = new ArrayList<>();
    private double[][] projB = new double[0][0];
    private double[] residual;
    private double beta;
    private double normEst;
    private final List<EigenPairResult> locked = new ArrayList<>();
    private final List<double[]> lockedVecs = new ArrayList<>();
    private final List<IterationRecord> history = new ArrayList<>();
    private int outer;
    private int restartCount;

    public LanczosSolver(CsrMatrix matrix, SolverOptions options, BooleanSupplier cancelled,
                         long deadlineMillis) {
        this.matrix = matrix;
        this.options = options;
        this.cancelled = cancelled;
        this.deadlineMillis = deadlineMillis;
        int want = Math.max(2 * options.numEigenvalues() + 8, options.numEigenvalues() + 16);
        this.maxBasis = Math.max(1, Math.min(matrix.n(), want));
    }

    /** Solve from scratch. */
    public EigenResult solve(SnapshotStore store, String taskId) {
        initState();
        return run(store, taskId);
    }

    /**
     * Resume from the latest snapshot; produces results identical to an
     * uninterrupted run. Falls back to a fresh solve when no snapshot exists
     * (crash before the first snapshot); fails loudly on a corrupt snapshot.
     */
    public EigenResult resume(SnapshotStore store, String taskId) {
        SolverSnapshot s = store.loadLatest(taskId).orElse(null);
        if (s == null) {
            initState();
        } else if (s.version != SolverSnapshot.FORMAT_VERSION || s.basis == null) {
            return EigenResult.failure(SolveStatus.FAILED, ErrorCode.SNAPSHOT_CORRUPT,
                    "snapshot for task " + taskId + " has unsupported format", List.of(), 0, 0);
        } else {
            loadState(s);
        }
        return run(store, taskId);
    }

    // ------------------------------------------------------------------ state

    private void initState() {
        basis.clear();
        projB = new double[0][0];
        locked.clear();
        lockedVecs.clear();
        history.clear();
        outer = 0;
        restartCount = 0;
        normEst = 0.0;
        freshStartVector();
    }

    private void loadState(SolverSnapshot s) {
        basis.clear();
        for (double[] col : s.basis) {
            basis.add(col);
        }
        projB = s.projectedB;
        residual = s.residual;
        beta = s.beta;
        normEst = s.normEstimate;
        locked.clear();
        locked.addAll(s.locked);
        lockedVecs.clear();
        for (EigenPairResult p : s.locked) {
            lockedVecs.add(p.eigenvector());
        }
        history.clear();
        history.addAll(s.history);
        outer = s.outerIteration;
        restartCount = s.restartCount;
    }

    private SolverSnapshot toSnapshot() {
        SolverSnapshot s = new SolverSnapshot();
        s.outerIteration = outer;
        s.restartCount = restartCount;
        s.basis = basis.toArray(new double[0][]);
        s.projectedB = projB;
        s.residual = residual;
        s.beta = beta;
        s.normEstimate = normEst;
        s.history = new ArrayList<>(history);
        s.locked = new ArrayList<>(locked);
        return s;
    }

    /** Deterministic unit vector orthogonal to locked pairs and the basis. */
    private void freshStartVector() {
        Random rng = new Random(options.seed() + RESTART_SALT * restartCount);
        restartCount++;
        int n = matrix.n();
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = rng.nextGaussian();
        }
        for (int pass = 0; pass < 2; pass++) {
            for (double[] lv : lockedVecs) {
                axpy(v, lv, -dot(v, lv));
            }
            for (double[] q : basis) {
                axpy(v, q, -dot(v, q));
            }
        }
        residual = v;
        beta = norm(v);
    }

    // ------------------------------------------------------------------- run

    private EigenResult run(SnapshotStore store, String taskId) {
        long t0 = System.currentTimeMillis();
        int n = matrix.n();
        int nev = options.numEigenvalues();

        while (true) {
            EigenResult control = checkControl(t0);
            if (control != null) {
                return control;
            }

            // 1) Expand the subspace to maxBasis.
            while (basis.size() < maxBasis) {
                control = checkControl(t0);
                if (control != null) {
                    return control;
                }
                ExpandOutcome oc = expandOne();
                if (oc == ExpandOutcome.DEGENERATE) {
                    return EigenResult.failure(SolveStatus.FAILED, ErrorCode.NUMERICAL_DEGENERACY,
                            "non-finite value in recurrence at outer iteration " + outer
                                    + " (input likely causes overflow)",
                            history, history.size(), elapsed(t0));
                }
                if (oc == ExpandOutcome.BREAKDOWN) {
                    if (basis.size() + locked.size() >= n) {
                        break; // whole space spanned: projected problem is exact
                    }
                    freshStartVector(); // invariant-subspace breakdown: restart direction
                }
            }

            // 2) Rayleigh-Ritz on the projected matrix.
            int k = basis.size();
            double[][] denseB = new double[k][k];
            for (int j = 0; j < k; j++) {
                System.arraycopy(projB[j], 0, denseB[j], 0, k);
            }
            DenseSymmetricEigen.Decomposition eig = DenseSymmetricEigen.decompose(denseB);
            for (double v : eig.eigenvalues()) {
                normEst = Math.max(normEst, Math.abs(v));
            }
            double tolAbs = options.tolerance() * Math.max(1.0, normEst);

            // 3) Lock contiguous converged pairs from the bottom of the spectrum.
            int remaining = nev - locked.size();
            int lockedBefore = locked.size();
            double bestUnconverged = Double.POSITIVE_INFINITY;
            int candidates = Math.min(remaining, k);
            for (int i = 0; i < candidates; i++) {
                double resEst = beta * Math.abs(eig.eigenvectors()[i][k - 1]);
                if (resEst <= tolAbs) {
                    lockPair(eig.eigenvalues()[i], eig.eigenvectors()[i]);
                } else {
                    bestUnconverged = resEst;
                    break;
                }
            }
            if (bestUnconverged == Double.POSITIVE_INFINITY) {
                bestUnconverged = 0.0;
            }
            history.add(new IterationRecord(outer, k, bestUnconverged, locked.size()));

            // 4) Termination checks.
            if (locked.size() >= nev) {
                return EigenResult.success(filterByInterval(locked), history,
                        history.size(), elapsed(t0));
            }
            if (history.size() >= options.maxIterations()) {
                return EigenResult.failure(SolveStatus.FAILED, ErrorCode.NOT_CONVERGED,
                        "not converged after " + history.size() + " outer iterations; locked "
                                + locked.size() + "/" + nev + " pairs, best residual "
                                + bestUnconverged,
                        history, history.size(), elapsed(t0));
            }

            // 5) Thick restart: keep the smallest unconverged Ritz vectors.
            restart(eig, locked.size() - lockedBefore, k);
            outer++;

            // 6) Periodic snapshot for crash recovery.
            if (store != null && options.snapshotEvery() > 0 && outer % options.snapshotEvery() == 0) {
                store.save(taskId, toSnapshot());
            }
        }
    }

    private EigenResult checkControl(long t0) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            return EigenResult.failure(SolveStatus.CANCELLED, ErrorCode.CANCELLED,
                    "task cancelled by caller", history, history.size(), elapsed(t0));
        }
        if (deadlineMillis > 0 && System.currentTimeMillis() > deadlineMillis) {
            return EigenResult.failure(SolveStatus.TIMEOUT, ErrorCode.TIMEOUT,
                    "task exceeded its wall-clock budget", history, history.size(), elapsed(t0));
        }
        return null;
    }

    private long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }

    // --------------------------------------------------------------- expand

    /** Add one basis vector via Arnoldi step with full (twice) reorthogonalization. */
    private ExpandOutcome expandOne() {
        if (beta == 0.0) {
            return ExpandOutcome.BREAKDOWN;
        }
        int k = basis.size();
        double inv = 1.0 / beta;
        double[] q = new double[matrix.n()];
        for (int i = 0; i < q.length; i++) {
            q[i] = residual[i] * inv;
        }
        double[] w = matrix.multiply(q);
        if (nonFinite(w)) {
            return ExpandOutcome.DEGENERATE;
        }
        double[] h = new double[k + 1];
        double[] r = w;
        // Two DGKS passes: orthogonalize against old basis, the new vector q
        // itself (its projection is the diagonal alpha), and locked vectors.
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < k; j++) {
                double c = dot(basis.get(j), r);
                h[j] += c;
                r = axpy(r, basis.get(j), -c);
            }
            double alpha = dot(q, r);
            h[k] += alpha;
            r = axpy(r, q, -alpha);
            for (double[] lv : lockedVecs) {
                r = axpy(r, lv, -dot(r, lv));
            }
        }
        double newBeta = norm(r);
        if (Double.isNaN(newBeta) || Double.isInfinite(newBeta)) {
            return ExpandOutcome.DEGENERATE;
        }
        // Commit the new basis vector and grow the projected matrix.
        basis.add(q);
        double[][] newB = new double[k + 1][k + 1];
        for (int j = 0; j < k; j++) {
            System.arraycopy(projB[j], 0, newB[j], 0, k);
        }
        for (int j = 0; j < k; j++) {
            newB[j][k] = h[j];
            newB[k][j] = h[j];
        }
        newB[k][k] = h[k];
        projB = newB;
        residual = r;
        beta = newBeta;
        if (newBeta <= BREAKDOWN_REL * Math.max(1.0, normEst)) {
            return ExpandOutcome.BREAKDOWN;
        }
        return ExpandOutcome.OK;
    }

    // ----------------------------------------------------------- Ritz pairs

    /** Compute the Ritz vector in R^n, normalize, sign-fix, record as committed. */
    private void lockPair(double theta, double[] y) {
        int k = basis.size();
        int n = matrix.n();
        double[] x = new double[n];
        for (int j = 0; j < k; j++) {
            double[] q = basis.get(j);
            double yj = y[j];
            for (int i = 0; i < n; i++) {
                x[i] += q[i] * yj;
            }
        }
        double xn = norm(x);
        if (xn > 0) {
            double s = 1.0 / xn;
            for (int i = 0; i < n; i++) {
                x[i] *= s;
            }
        }
        fixSign(x);
        // Honest residual from an explicit SpMV.
        double[] ax = matrix.multiply(x);
        double res = 0.0;
        for (int i = 0; i < n; i++) {
            double d = ax[i] - theta * x[i];
            res += d * d;
        }
        res = Math.sqrt(res);
        locked.add(new EigenPairResult(theta, x, res, true, outer));
        lockedVecs.add(x);
    }

    /** Keep Ritz vectors [fromIndex, fromIndex+keep) as the new active basis. */
    private void restart(DenseSymmetricEigen.Decomposition eig, int fromIndex, int k) {
        int remaining = options.numEigenvalues() - locked.size();
        int keep = Math.min(remaining + 2, k - fromIndex);
        if (keep <= 0) {
            freshStartVector();
            basis.clear();
            projB = new double[0][0];
            return;
        }
        int n = matrix.n();
        List<double[]> newBasis = new ArrayList<>(keep);
        for (int j = 0; j < keep; j++) {
            double[] y = eig.eigenvectors()[fromIndex + j];
            double[] x = new double[n];
            for (int c = 0; c < k; c++) {
                double[] q = basis.get(c);
                double yc = y[c];
                for (int i = 0; i < n; i++) {
                    x[i] += q[i] * yc;
                }
            }
            newBasis.add(x);
        }
        double[][] newB = new double[keep][keep];
        for (int j = 0; j < keep; j++) {
            newB[j][j] = eig.eigenvalues()[fromIndex + j];
        }
        basis.clear();
        basis.addAll(newBasis);
        projB = newB;
    }

    private List<EigenPairResult> filterByInterval(List<EigenPairResult> pairs) {
        Double lo = options.intervalMin();
        Double hi = options.intervalMax();
        if (lo == null && hi == null) {
            return pairs;
        }
        List<EigenPairResult> out = new ArrayList<>();
        for (EigenPairResult p : pairs) {
            if ((lo == null || p.eigenvalue() >= lo) && (hi == null || p.eigenvalue() <= hi)) {
                out.add(p);
            }
        }
        return out;
    }

    // -------------------------------------------------------------- helpers

    /** Fixed sign rule: the largest-magnitude component is made positive. */
    static void fixSign(double[] v) {
        int idx = 0;
        double best = 0.0;
        for (int i = 0; i < v.length; i++) {
            if (Math.abs(v[i]) > best) {
                best = Math.abs(v[i]);
                idx = i;
            }
        }
        if (v[idx] < 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = -v[i];
            }
        }
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** @return a + scale*b (new array) */
    private static double[] axpy(double[] a, double[] b, double scale) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] + scale * b[i];
        }
        return out;
    }

    private static double norm(double[] a) {
        double s = 0.0;
        for (double v : a) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    private static boolean nonFinite(double[] a) {
        for (double v : a) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return true;
            }
        }
        return false;
    }
}
