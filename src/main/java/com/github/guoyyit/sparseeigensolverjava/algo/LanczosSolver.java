package com.github.guoyyit.sparseeigensolverjava.algo;

import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import com.github.guoyyit.sparseeigensolverjava.model.EigenPairResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic Lanczos eigensolver for the smallest eigenvalues of a real
 * symmetric sparse matrix.
 *
 * <p>Characteristics:
 * <ul>
 *   <li>Full (twice repeated classical Gram-Schmidt) reorthogonalization keeps
 *       the Krylov basis numerically orthonormal; the matrix is only accessed
 *       through sparse SpMV and is never densified.</li>
 *   <li>The starting vector is generated from the request seed with a fixed
 *       64-bit LCG, so identical (input, seed, config) runs are bit-identical.</li>
 *   <li>Every completed iteration is described completely by {@link LanczosState}
 *       (basis Q, alphas, betas), which makes periodic snapshots and exact
 *       continuation after process restart possible.</li>
 *   <li>Ritz values of the small tridiagonal matrix come from a deterministic
 *       Sturm-bisection and the needed extremal Ritz vectors from inverse
 *       iteration; the residual estimate is {@code |beta_{m+1}| * |s_m|} and the
 *       true residual {@code ||A*y - theta*y||} is reported for every pair.</li>
 * </ul>
 */
@Component
public class LanczosSolver {

    /** Cooperative cancellation / deadline / snapshot callbacks. */
    public interface Control {
        boolean isCancelled();

        long deadlineMs();

        /** Called after every completed Lanczos step; may persist a snapshot. */
        void onStepComplete(LanczosState state);
    }

    private static final double ORTH_TINY = 1.0e-300;

    public LanczosState init(String requestId, CsrMatrix matrix, long seed, int nev,
                             double tolerance, Double intervalLower,
                             Double intervalUpper, int maxIterations) {
        int n = matrix.getN();
        if (nev < 1 || nev > n) {
            throw new SolverException(ErrorCode.INVALID_NEV,
                    "nev must be in [1,n]; got " + nev + " with n=" + n);
        }
        if (maxIterations < nev || maxIterations > n) {
            throw new SolverException(ErrorCode.INVALID_MAX_ITERATIONS,
                    "maxIterations must be in [nev,n]; got " + maxIterations);
        }
        LanczosState st = new LanczosState();
        st.requestId = requestId;
        st.seed = seed;
        st.nev = nev;
        st.tolerance = tolerance;
        st.intervalLower = intervalLower;
        st.intervalUpper = intervalUpper;
        st.n = n;
        st.maxIterations = maxIterations;
        st.j = 0;
        st.status = "RUNNING";
        st.createdAt = System.currentTimeMillis();
        st.updatedAt = st.createdAt;

        st.basis = new double[maxIterations + 1][];
        st.alphas = new double[maxIterations];
        st.betas = new double[maxIterations];

        // Deterministic starting vector from the seed (LCG).
        double[] q1 = new double[n];
        long lcg = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        double norm = 0.0;
        for (int i = 0; i < n; i++) {
            lcg = lcg * 6364136223846793005L + 1442695040888963407L;
            double u = (lcg >>> 11) * 0x1.0p-53;
            q1[i] = u;
            norm += u * u;
        }
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm) || norm <= ORTH_TINY) {
            throw new SolverException(ErrorCode.NUMERICAL_BREAKDOWN,
                    "degenerate starting vector (norm=" + norm + ") for seed "
                            + seed);
        }
        scale(q1, 1.0 / norm);
        st.basis[0] = q1;
        return st;
    }

    /**
     * Runs Lanczos steps until convergence, the iteration budget is exhausted,
     * a numerical failure is detected, the deadline passes or the request is
     * cancelled. All outcome information is written into {@code state}.
     */
    public void run(LanczosState st, CsrMatrix a, Control control) {
        int m = st.maxIterations;
        double[] w = new double[st.n];
        double[] ax = new double[st.n];

        while (st.j < m) {
            if (control.isCancelled()) {
                st.status = "CANCELLED";
                st.message = "cancelled by client after " + st.j + " iterations";
                return;
            }
            if (System.currentTimeMillis() >= control.deadlineMs()) {
                st.status = "FAILED";
                st.errorCode = ErrorCode.TIME_LIMIT_EXCEEDED.name();
                st.message = "time limit reached after " + st.j + " iterations";
                return;
            }

            double[] qj = st.basis[st.j];
            a.multiply(qj, ax);

            // w = A q_j
            System.arraycopy(ax, 0, w, 0, st.n);

            // alpha_j = q_j^T A q_j
            double alpha = dot(qj, w);
            // w -= alpha_j q_j
            axpy(w, -alpha, qj);

            // Full reorthogonalization (two passes) against q1..qj.
            for (int pass = 0; pass < 2; pass++) {
                for (int k = 0; k <= st.j; k++) {
                    double c = dot(st.basis[k], w);
                    axpy(w, -c, st.basis[k]);
                }
            }
            double beta = norm2(w);

            if (!Double.isFinite(alpha) || !Double.isFinite(beta)) {
                st.status = "FAILED";
                st.errorCode = ErrorCode.NUMERICAL_BREAKDOWN.name();
                st.message = "non-finite Lanczos coefficient at iteration "
                        + (st.j + 1);
                retainDiagnostics(st);
                st.finished = true;
                return;
            }

            st.alphas[st.j] = alpha;
            st.betas[st.j] = beta;
            st.j++;
            st.basis[st.j] = w.clone();

            if (beta <= ORTH_TINY) {
                // Invariant subspace found: the current Ritz values are exact
                // but the subspace cannot be expanded further.
                if (tryFinalize(st, a)) {
                    return;
                }
                st.status = "FAILED";
                st.errorCode = ErrorCode.NUMERICAL_BREAKDOWN.name();
                st.message = "early invariant subspace (beta=" + beta
                        + ") with insufficient converged Ritz values at iteration "
                        + st.j;
                retainDiagnostics(st);
                st.finished = true;
                return;
            }
            scale(st.basis[st.j], 1.0 / beta);

            st.updatedAt = System.currentTimeMillis();
            control.onStepComplete(st);

            if (tryFinalize(st, a)) {
                return;
            }
        }

        // Iteration budget exhausted without convergence.
        st.status = "FAILED";
        st.errorCode = ErrorCode.NOT_CONVERGED.name();
        st.message = "did not converge " + st.nev
                + " requested eigenpair(s) within " + m + " iterations";
        retainDiagnostics(st);
        st.finished = true;
    }

    /**
     * Checks whether the {@code nev} smallest in-interval Ritz pairs all meet
     * the tolerance. If so, commits them into the state (journaled results).
     * Returns true when the solve is finished (converged).
     */
    private boolean tryFinalize(LanczosState st, CsrMatrix a) {
        int m = st.j;
        if (m < st.nev) {
            return false;
        }
        double[] diag = new double[m];
        double[] off = new double[m];
        for (int i = 0; i < m; i++) {
            diag[i] = st.alphas[i];
            if (i < m - 1) {
                off[i] = st.betas[i];
            }
        }

        // 1) Cheap, robust: all Ritz values by Sturm bisection (no vectors).
        double[] ritz;
        try {
            ritz = TridiagonalEigensolver.eigenvalues(diag, off);
        } catch (SolverException ex) {
            st.status = "FAILED";
            st.errorCode = ex.getErrorCode().name();
            st.message = ex.getMessage();
            st.finished = true;
            return true;
        }

        // 2) Ranks of in-interval Ritz values in ascending order.
        int[] eligible = new int[m];
        int eligibleCount = 0;
        for (int i = 0; i < m; i++) {
            double theta = ritz[i];
            boolean inInterval = (st.intervalLower == null
                    || theta >= st.intervalLower)
                    && (st.intervalUpper == null || theta <= st.intervalUpper);
            if (inInterval) {
                eligible[eligibleCount++] = i;
            }
        }
        if (eligibleCount < st.nev) {
            return false;
        }

        // 3) Bounding relation: residual of the nev-th candidate bounds the
        // residuals of the nev smallest. Only invert on those low ranks (plus a
        // small safety margin); never on interior clustered eigenvalues.
        int probe = Math.min(m - 1,
                Math.max(eligible[st.nev - 1] + 2, st.nev + 3));
        TridiagonalEigensolver.Result t;
        try {
            t = TridiagonalEigensolver.solvePrefix(diag, off, probe);
        } catch (SolverException ex) {
            st.status = "FAILED";
            st.errorCode = ex.getErrorCode().name();
            st.message = ex.getMessage();
            st.finished = true;
            return true;
        }

        // The cheap Ritz residual estimate |beta_{m+1} * s_m| is valid only
        // while a next Lanczos vector exists.
        boolean haveNextBeta = m < st.maxIterations;
        double betaM = Math.abs(st.betas[m - 1]);
        boolean estimatesOk = true;
        double[] estimates = new double[st.nev];
        if (haveNextBeta) {
            for (int r = 0; r < st.nev; r++) {
                int idx = eligible[r];
                estimates[r] = betaM * Math.abs(t.eigenvectors[idx][m - 1]);
                if (!Double.isFinite(estimates[r]) || estimates[r] > st.tolerance) {
                    estimatesOk = false;
                }
            }
        }

        // Assemble candidate Ritz vectors and their TRUE residuals ||Ay-theta y||.
        double[] values = new double[st.nev];
        double[][] vectors = new double[st.nev][st.n];
        double[] residuals = new double[st.nev];
        double[] y = new double[st.n];
        double[] ay = new double[st.n];
        for (int r = 0; r < st.nev; r++) {
            int idx = eligible[r];
            double theta = t.eigenvalues[idx];
            double[] sv = t.eigenvectors[idx];
            for (int l = 0; l < m; l++) {
                axpyInto(y, sv[l], st.basis[l]);
            }
            fixNormalizationAndSign(y);
            a.multiply(y, ay);
            values[r] = theta;
            vectors[r] = y.clone();
            residuals[r] = residualNorm(ay, y, theta);
            java.util.Arrays.fill(y, 0.0);
        }

        // Never report success for an unconverged pair: while the cheap estimate
        // is available it gates cheaply; when the Krylov space is exhausted the
        // true residual is the authority.
        if (haveNextBeta && !estimatesOk) {
            return false;
        }
        for (int r = 0; r < st.nev; r++) {
            if (!Double.isFinite(residuals[r]) || residuals[r] > st.tolerance) {
                // exhausted subspace cannot improve any further -> degenerate
                if (!haveNextBeta) {
                    st.status = "FAILED";
                    st.errorCode = ErrorCode.NOT_CONVERGED.name();
                    st.message = "Krylov space exhausted at iteration " + m
                            + " but eigenpair " + r + " true residual "
                            + residuals[r] + " exceeds tolerance " + st.tolerance;
                    st.finished = true;
                    retainDiagnostics(st);
                    return true;
                }
                return false;
            }
        }

        // All requested pairs converged: commit them (journaled once).
        st.finalValues = values;
        st.finalVectors = vectors;
        st.finalResiduals = residuals;
        st.finalConverged = new boolean[st.nev];
        java.util.Arrays.fill(st.finalConverged, true);
        st.committedCount = st.nev;
        st.status = "CONVERGED";
        st.finished = true;
        st.message = "converged at iteration " + m;
        st.updatedAt = System.currentTimeMillis();
        return true;
    }

    /** Builds DTO results from a converged state. */
    public List<EigenPairResult> assembleResults(LanczosState st) {
        List<EigenPairResult> out = new ArrayList<>();
        if (st.finalValues == null) {
            return out;
        }
        for (int r = 0; r < st.finalValues.length; r++) {
            EigenPairResult p = new EigenPairResult();
            p.eigenvalue = st.finalValues[r];
            p.eigenvector = st.finalVectors[r].clone();
            p.residual = st.finalResiduals[r];
            p.converged = st.finalConverged[r];
            out.add(p);
        }
        return out;
    }

    private void retainDiagnostics(LanczosState st) {
        int m = st.j;
        if (m <= 0) {
            return;
        }
        double[] diag = new double[m];
        double[] off = new double[m];
        for (int i = 0; i < m; i++) {
            diag[i] = st.alphas[i];
            if (i < m - 1) {
                off[i] = st.betas[i];
            }
        }
        try {
            double[] values = TridiagonalEigensolver.eigenvalues(diag, off);
            st.lastRitzValues = values.clone();
            st.lastResidualEstimates = new double[m];
            // best-effort residual estimates unavailable without vectors; provide
            // a coarse diagnostic from the trailing off-diagonal size.
            double betaM = Math.abs(st.betas[m - 1]);
            for (int i = 0; i < m; i++) {
                st.lastResidualEstimates[i] = betaM;
            }
        } catch (RuntimeException ignore) {
            // Diagnostics are best-effort.
        }
    }

    /**
     * Normalizes the vector to unit 2-norm and fixes the sign: the component
     * with the largest magnitude (smallest index on ties) is non-negative.
     */
    static void fixNormalizationAndSign(double[] v) {
        double nrm = norm2(v);
        if (!Double.isFinite(nrm) || nrm <= ORTH_TINY) {
            throw new SolverException(ErrorCode.NUMERICAL_BREAKDOWN,
                    "cannot normalize eigenvector with norm " + nrm);
        }
        int pivot = 0;
        double best = Math.abs(v[0]);
        for (int i = 1; i < v.length; i++) {
            if (Math.abs(v[i]) > best) {
                best = Math.abs(v[i]);
                pivot = i;
            }
        }
        double sign = v[pivot] < 0.0 ? -1.0 / nrm : 1.0 / nrm;
        scale(v, sign);
    }

    private static double residualNorm(double[] ay, double[] y, double theta) {
        double s = 0.0;
        for (int i = 0; i < y.length; i++) {
            double d = ay[i] - theta * y[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    private static double dot(double[] x, double[] y) {
        double s = 0.0;
        for (int i = 0; i < x.length; i++) {
            s += x[i] * y[i];
        }
        return s;
    }

    private static double norm2(double[] x) {
        return Math.sqrt(dot(x, x));
    }

    private static void scale(double[] x, double f) {
        for (int i = 0; i < x.length; i++) {
            x[i] *= f;
        }
    }

    private static void axpy(double[] x, double a, double[] y) {
        for (int i = 0; i < x.length; i++) {
            x[i] += a * y[i];
        }
    }

    private static void axpyInto(double[] x, double a, double[] y) {
        for (int i = 0; i < x.length; i++) {
            x[i] += a * y[i];
        }
    }
}
