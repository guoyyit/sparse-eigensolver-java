package com.github.guoyyit.sparseeigensolverjava.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * Immutable real symmetric sparse matrix in Compressed Sparse Row form.
 *
 * <p>Only the upper triangle (including the diagonal) is stored: for every
 * stored entry (i, j, v) the caller must have {@code j >= i}, and the lower
 * triangle is implied by symmetry. The matrix is never densified.</p>
 *
 * @param n          matrix dimension (n x n)
 * @param rowPtr     row pointer array, length n+1, non-decreasing, rowPtr[0]=0
 * @param colIndices column indices, length nnz, each row sorted ascending
 * @param values     nonzero values, length nnz
 */
public record CsrMatrix(int n, int[] rowPtr, int[] colIndices, double[] values)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public int nnz() {
        return values.length;
    }

    /**
     * Sparse matrix-vector product y = A*x exploiting symmetry:
     * each stored (i,j,v) contributes v*x[j] to y[i] and, when i != j,
     * v*x[i] to y[j]. O(nnz) time, O(n) extra space.
     */
    public double[] multiply(double[] x) {
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double xi = x[i];
            double acc = 0.0;
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                int j = colIndices[k];
                double v = values[k];
                acc += v * x[j];
                if (j != i) {
                    y[j] += v * xi;
                }
            }
            y[i] += acc;
        }
        return y;
    }

    /** Deep copy of the structural arrays (defensive copy for isolation). */
    public CsrMatrix copy() {
        return new CsrMatrix(n, rowPtr.clone(), colIndices.clone(), values.clone());
    }
}
