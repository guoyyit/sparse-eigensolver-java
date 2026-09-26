package com.github.guoyyit.sparseeigensolverjava.algo;

import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;

/**
 * Eigen decomposition of a real symmetric tridiagonal matrix.
 *
 * <p>Eigenvalues are computed deterministically by Sturm-sequence bisection and
 * eigenvectors by inverse iteration with tridiagonal Thomas solves. This avoids
 * fragile shifted-QL bookkeeping while remaining cheap because the input is only
 * the small Lanczos projection matrix. Results are in ascending eigenvalue
 * order, every eigenvector has unit 2-norm, and the component with largest
 * magnitude (smallest index on ties) is non-negative.
 */
public final class TridiagonalEigensolver {

    public static final class Result {
        public final double[] eigenvalues;
        /** vectors[i] is the unit eigenvector paired with eigenvalues[i]. */
        public final double[][] eigenvectors;

        public Result(double[] eigenvalues, double[][] eigenvectors) {
            this.eigenvalues = eigenvalues;
            this.eigenvectors = eigenvectors;
        }
    }

    private static final int BISECT_STEPS = 200;
    private static final int INVERSE_STEPS = 20;
    private static final double RESIDUAL_GOAL = 1.0e-11;
    private static final double TINY = 1.0e-300;

    private TridiagonalEigensolver() {
    }

    /**
     * @param diag    diagonal d[0..m-1]
     * @param offdiag offdiag[i] is the off-diagonal between indices i and i+1,
     *                length m-1 (an m-length array is accepted, last entry unused)
     */
    public static Result solve(double[] diag, double[] offdiag) {
        int m = diag.length;
        return solvePrefix(diag, offdiag, m - 1);
    }

    /** All eigenvalues via Sturm bisection (no eigenvectors). */
    public static double[] eigenvalues(double[] diag, double[] offdiag) {
        Prepared p = prepare(diag, offdiag);
        int m = p.d.length;
        double[] values = new double[m];
        for (int k = 0; k < m; k++) {
            values[k] = bisect(p.d, p.off, k, p.low, p.high, p.tol);
        }
        return values;
    }

    /**
     * Computes all eigenvalues and the eigenvectors for ranks {@code 0..maxRank}
     * (smallest eigenvalues). Other vector slots are {@code null}. Extremal
     * Ritz vectors are well conditioned, so this avoids inverse iteration on
     * clustered interior eigenvalues that callers never need.
     */
    public static Result solvePrefix(double[] diag, double[] offdiag, int maxRank) {
        Prepared p = prepare(diag, offdiag);
        int m = p.d.length;
        if (m == 1) {
            return new Result(new double[] {p.d[0]},
                    new double[][] {new double[] {1.0}});
        }
        double[] values = new double[m];
        double[][] vectors = new double[m][];
        for (int k = 0; k < m; k++) {
            values[k] = bisect(p.d, p.off, k, p.low, p.high, p.tol);
        }
        for (int k = 0; k <= maxRank; k++) {
            vectors[k] = inverseIterate(p.d, p.off, values[k], k, vectors);
        }
        return new Result(values, vectors);
    }

    private static final class Prepared {
        double[] d;
        double[] off;
        double low;
        double high;
        double tol;
    }

    private static Prepared prepare(double[] diag, double[] offdiag) {
        int m = diag.length;
        Prepared p = new Prepared();
        p.d = diag.clone();
        p.off = new double[Math.max(0, m - 1)];
        for (int i = 0; i < p.off.length; i++) {
            p.off[i] = offdiag[i];
            if (!Double.isFinite(p.off[i])) {
                throw new SolverException(ErrorCode.NUMERICAL_BREAKDOWN,
                        "non-finite off-diagonal entry at index " + i);
            }
        }
        for (int i = 0; i < m; i++) {
            if (!Double.isFinite(p.d[i])) {
                throw new SolverException(ErrorCode.NUMERICAL_BREAKDOWN,
                        "non-finite diagonal entry at index " + i);
            }
        }
        p.low = gershgorinLow(p.d, p.off);
        p.high = gershgorinHigh(p.d, p.off);
        double scale = Math.max(1.0, Math.max(Math.abs(p.low), Math.abs(p.high)));
        p.tol = 1.0e-14 * scale;
        return p;
    }

    // ------------------------------------------------------------------
    // Sturm bisection
    // ------------------------------------------------------------------

    /**
     * Number of eigenvalues strictly smaller than x, using the LDL^T inertia of
     * (T - xI) (Sturm sequence count of negative pivots).
     */
    private static int countLess(double[] d, double[] off, double x) {
        int n = d.length;
        int count = 0;
        double pivot = d[0] - x;
        if (pivot == 0.0) {
            pivot = TINY;
        }
        if (pivot < 0.0) {
            count++;
        }
        for (int i = 1; i < n; i++) {
            double e = off[i - 1];
            pivot = (d[i] - x) - (e * e) / pivot;
            if (pivot == 0.0) {
                pivot = TINY;
            }
            if (pivot < 0.0) {
                count++;
            }
        }
        return count;
    }

    private static double bisect(double[] d, double[] off, int k, double low,
                                 double high, double tol) {
        double a = low;
        double b = high;
        for (int it = 0; it < BISECT_STEPS; it++) {
            double mid = 0.5 * (a + b);
            if (b - a <= tol || mid == a || mid == b) {
                return mid;
            }
            // countLess(mid) > k  =>  k-th eigenvalue lies left of mid
            if (countLess(d, off, mid) > k) {
                b = mid;
            } else {
                a = mid;
            }
        }
        return 0.5 * (a + b);
    }

    // ------------------------------------------------------------------
    // Inverse iteration
    // ------------------------------------------------------------------

    private static double[] inverseIterate(double[] d, double[] off, double mu,
                                           int k, double[][] previous) {
        int n = d.length;
        double[] b = null;
        double bestResidual = Double.POSITIVE_INFINITY;
        // A small number of deterministic restarts handles clustered eigenvalues
        // where the initial random vector has a tiny component along the target.
        for (int attempt = 0; attempt < 4; attempt++) {
            long lcg = 0x2545F4914F6CDD1DL
                    ^ ((long) k * 0x9E3779B97F4A7C15L)
                    ^ ((long) attempt * 0xC2B2AE3D27D4EB4FL);
            b = new double[n];
            for (int i = 0; i < n; i++) {
                lcg = lcg * 6364136223846793005L + 1442695040888963407L;
                b[i] = (lcg >>> 11) * 0x1.0p-53 - 0.5;
            }
            for (int j = 0; j < k; j++) {
                double c = dot(b, previous[j]);
                axpy(b, -c, previous[j]);
            }
            double nb0 = norm2(b);
            if (nb0 <= TINY) {
                continue;
            }
            scale(b, 1.0 / nb0);

            double[] y = new double[n];
            boolean ok = true;
            for (int step = 0; step < INVERSE_STEPS; step++) {
                solveThomas(d, off, mu, b, y);
                for (int pass = 0; pass < 2; pass++) {
                    for (int j = 0; j < k; j++) {
                        double c = dot(y, previous[j]);
                        axpy(y, -c, previous[j]);
                    }
                }
                double ny = norm2(y);
                if (!Double.isFinite(ny) || ny <= TINY) {
                    ok = false;
                    break;
                }
                double[] tmp = b;
                b = new double[n];
                scaleInto(y, 1.0 / ny, b);
                y = tmp;
            }
            if (ok) {
                double achieved = relativeResidual(d, off, mu, b);
                if (achieved <= RESIDUAL_GOAL) {
                    fixSign(b);
                    return b;
                }
            }
            bestResidual = Math.min(bestResidual,
                    ok ? relativeResidual(d, off, mu, b) : Double.POSITIVE_INFINITY);
        }
        throw new SolverException(ErrorCode.NUMERICAL_BREAKDOWN,
                "inverse iteration failed to converge at index " + k
                        + " (best relative residual " + bestResidual + ")");
    }

    private static double relativeResidual(double[] d, double[] off, double mu,
                                           double[] v) {
        double normV = norm2(v);
        if (!Double.isFinite(normV) || normV <= TINY) {
            return Double.POSITIVE_INFINITY;
        }
        double res = 0.0;
        for (int i = 0; i < v.length; i++) {
            double tv = d[i] * v[i];
            if (i > 0) {
                tv += off[i - 1] * v[i - 1];
            }
            if (i < v.length - 1) {
                tv += off[i] * v[i + 1];
            }
            double r = tv - mu * v[i];
            res += r * r;
        }
        double scale = Math.max(1.0, Math.abs(mu));
        return Math.sqrt(res) / scale;
    }

    /** Solves (T - mu I) y = rhs via LU (Thomas) with a pivot safety floor. */
    private static void solveThomas(double[] d, double[] off, double mu,
                                    double[] rhs, double[] y) {
        int n = d.length;
        double[] cp = new double[n - 1];
        double[] u = rhs.clone();
        double pivot = d[0] - mu;
        if (Math.abs(pivot) < TINY) {
            pivot = pivot < 0.0 ? -TINY : TINY;
        }
        double[] dp = new double[n];
        dp[0] = pivot;
        for (int i = 0; i < n - 1; i++) {
            double e = off[i];
            cp[i] = e / dp[i];
            double next = d[i + 1] - mu - cp[i] * e;
            if (Math.abs(next) < TINY) {
                next = next < 0.0 ? -TINY : TINY;
            }
            dp[i + 1] = next;
            u[i + 1] -= cp[i] * u[i];
        }
        y[n - 1] = u[n - 1] / dp[n - 1];
        for (int i = n - 2; i >= 0; i--) {
            y[i] = (u[i] - off[i] * y[i + 1]) / dp[i];
        }
    }

    // ------------------------------------------------------------------
    // Gershgorin bounds and small linear algebra
    // ------------------------------------------------------------------

    private static double gershgorinLow(double[] d, double[] off) {
        int n = d.length;
        double low = d[0] - (n > 1 ? Math.abs(off[0]) : 0.0);
        for (int i = 1; i < n - 1; i++) {
            low = Math.min(low, d[i] - Math.abs(off[i - 1]) - Math.abs(off[i]));
        }
        if (n > 1) {
            low = Math.min(low, d[n - 1] - Math.abs(off[n - 2]));
        }
        return low;
    }

    private static double gershgorinHigh(double[] d, double[] off) {
        int n = d.length;
        double high = d[0] + (n > 1 ? Math.abs(off[0]) : 0.0);
        for (int i = 1; i < n - 1; i++) {
            high = Math.max(high, d[i] + Math.abs(off[i - 1]) + Math.abs(off[i]));
        }
        if (n > 1) {
            high = Math.max(high, d[n - 1] + Math.abs(off[n - 2]));
        }
        return high;
    }

    private static void fixSign(double[] v) {
        int pivot = 0;
        double best = Math.abs(v[0]);
        for (int i = 1; i < v.length; i++) {
            if (Math.abs(v[i]) > best) {
                best = Math.abs(v[i]);
                pivot = i;
            }
        }
        if (v[pivot] < 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = -v[i];
            }
        }
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

    private static void scaleInto(double[] src, double f, double[] dst) {
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i] * f;
        }
    }

    private static void axpy(double[] x, double a, double[] y) {
        for (int i = 0; i < x.length; i++) {
            x[i] += a * y[i];
        }
    }
}
