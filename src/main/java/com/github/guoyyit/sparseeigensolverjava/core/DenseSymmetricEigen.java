package com.github.guoyyit.sparseeigensolverjava.core;

/**
 * Eigendecomposition of small dense symmetric matrices (the projected
 * Rayleigh-Ritz problem, dimension = subspace size, typically &lt; 100).
 *
 * <p>Uses the classical cyclic Jacobi method: deterministic sweep order,
 * fixed iteration cap, no randomness, so identical input gives bit-identical
 * output. Cost is O(m^3) on the small projected matrix only.</p>
 */
public final class DenseSymmetricEigen {

    private static final int MAX_SWEEPS = 100;
    private static final double EPS = 1e-14;

    private DenseSymmetricEigen() {
    }

    /**
     * Result of a dense symmetric eigendecomposition.
     *
     * @param eigenvalues  ascending eigenvalues, length n
     * @param eigenvectors column j is the eigenvector for eigenvalues[j],
     *                     stored as eigenvectors[j] (each length n)
     */
    public record Decomposition(double[] eigenvalues, double[][] eigenvectors) {
    }

    /**
     * @param symmetricMatrix row-major n x n symmetric matrix (not modified)
     * @return ascending eigenpairs; eigenvector signs are sign-fixed
     */
    public static Decomposition decompose(double[][] symmetricMatrix) {
        int n = symmetricMatrix.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(symmetricMatrix[i], 0, a[i], 0, n);
        }
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            v[i][i] = 1.0;
        }

        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double off = offNorm(a, n);
            if (off <= EPS * Math.max(1.0, diagNorm(a, n))) {
                break;
            }
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (a[p][q] != 0.0) {
                        rotate(a, v, n, p, q);
                    }
                }
            }
        }

        // Collect and sort ascending by eigenvalue (selection sort: deterministic).
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = a[i][i];
        }
        double[][] vectors = new double[n][n]; // vectors[k] = eigenvector k
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                vectors[k][i] = v[i][k];
            }
        }
        for (int i = 0; i < n - 1; i++) {
            int min = i;
            for (int j = i + 1; j < n; j++) {
                if (values[j] < values[min]) {
                    min = j;
                }
            }
            if (min != i) {
                double tv = values[i];
                values[i] = values[min];
                values[min] = tv;
                double[] tt = vectors[i];
                vectors[i] = vectors[min];
                vectors[min] = tt;
            }
        }
        return new Decomposition(values, vectors);
    }

    private static double offNorm(double[][] a, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                sum += a[i][j] * a[i][j];
            }
        }
        return Math.sqrt(2.0 * sum);
    }

    private static double diagNorm(double[][] a, int n) {
        double max = 0.0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, Math.abs(a[i][i]));
        }
        return max;
    }

    /** Jacobi rotation zeroing a[p][q], accumulating eigenvectors in v. */
    private static void rotate(double[][] a, double[][] v, int n, int p, int q) {
        double app = a[p][p];
        double aqq = a[q][q];
        double apq = a[p][q];
        // Stable rotation angle (Golub & Van Loan, Alg. 8.4.1).
        double theta = (aqq - app) / (2.0 * apq);
        double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(1.0 + theta * theta));
        if (theta == 0.0) {
            t = 1.0;
        }
        double c = 1.0 / Math.sqrt(1.0 + t * t);
        double s = t * c;

        for (int k = 0; k < n; k++) {
            double akp = a[k][p];
            double akq = a[k][q];
            a[k][p] = c * akp - s * akq;
            a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < n; k++) {
            double apk = a[p][k];
            double aqk = a[q][k];
            a[p][k] = c * apk - s * aqk;
            a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < n; k++) {
            double vkp = v[k][p];
            double vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = s * vkp + c * vkq;
        }
    }
}
