package com.github.guoyyit.sparseeigensolverjava.core;

import java.util.Arrays;

/**
 * Immutable real symmetric matrix stored in compressed sparse row (CSR) format.
 *
 * <p>Only the upper triangle is stored: for every entry {@code (i, j, v)} it is
 * guaranteed that {@code j >= i}. Diagonal entries are stored once. The sparse
 * matrix-vector product exploits symmetry and never builds dense temporary
 * storage, so its cost is {@code O(nnz)} memory {@code O(n)}.
 */
public final class CsrMatrix {

    private final int n;
    private final int[] rowPtr;
    private final int[] colIndex;
    private final double[] values;

    /**
     * Builds a matrix from validated CSR arrays. The arrays are defensively
     * copied; validation itself is performed by {@link MatrixValidator}.
     */
    public CsrMatrix(int n, int[] rowPtr, int[] colIndex, double[] values) {
        this.n = n;
        this.rowPtr = rowPtr.clone();
        this.colIndex = colIndex.clone();
        this.values = values.clone();
    }

    public int getN() {
        return n;
    }

    public int[] getRowPtr() {
        return rowPtr.clone();
    }

    public int[] getColIndex() {
        return colIndex.clone();
    }

    public double[] getValues() {
        return values.clone();
    }

    /** Number of stored (upper triangle) entries. */
    public int getNnz() {
        return colIndex.length;
    }

    /**
     * Sparse matrix-vector product {@code y = A * x}.
     *
     * <p>For each stored upper-triangular entry {@code A[i,j] = v} with
     * {@code j > i} it contributes {@code v*x[j]} to {@code y[i]} and, using
     * symmetry, {@code v*x[i]} to {@code y[j]}. No dense n&times;n structure is
     * ever allocated.
     */
    public void multiply(double[] x, double[] y) {
        if (x.length != n || y.length != n) {
            throw new IllegalArgumentException("vector dimension mismatch");
        }
        Arrays.fill(y, 0.0);
        for (int i = 0; i < n; i++) {
            int start = rowPtr[i];
            int end = rowPtr[i + 1];
            double yi = y[i];
            for (int k = start; k < end; k++) {
                int j = colIndex[k];
                double v = values[k];
                yi += v * x[j];
                if (j != i) {
                    // lower-triangle contribution A[j,i] = A[i,j]
                    y[j] += v * x[i];
                }
            }
            y[i] = yi;
        }
    }
}
