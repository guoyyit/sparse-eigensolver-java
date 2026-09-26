package com.github.guoyyit.sparseeigensolverjava.tests;

import java.util.ArrayList;
import java.util.List;

/** Minimal helpers for building upper-triangle CSR arrays in tests. */
final class TestCsrDsl {

    static final class Matrix {
        int n;
        int[] rowPtr;
        int[] colIndex;
        double[] values;
    }

    private TestCsrDsl() {
    }

    /** Tridiagonal 1D Laplacian (diag 2, offdiag -1), stored upper triangle. */
    static Matrix laplacian(int n) {
        int[] rowPtr = new int[n + 1];
        List<Integer> colList = new ArrayList<>();
        List<Double> valList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rowPtr[i] = colList.size();
            colList.add(i);
            valList.add(2.0);
            if (i < n - 1) {
                colList.add(i + 1);
                valList.add(-1.0);
            }
        }
        rowPtr[n] = colList.size();
        Matrix m = new Matrix();
        m.n = n;
        m.rowPtr = rowPtr;
        m.colIndex = colList.stream().mapToInt(Integer::intValue).toArray();
        m.values = valList.stream().mapToDouble(Double::doubleValue).toArray();
        return m;
    }

    /** Diagonal matrix with the given diagonal values. */
    static Matrix diagonal(double... diagValues) {
        int n = diagValues.length;
        int[] rowPtr = new int[n + 1];
        int[] cols = new int[n];
        for (int i = 0; i < n; i++) {
            rowPtr[i] = i;
            cols[i] = i;
        }
        rowPtr[n] = n;
        Matrix m = new Matrix();
        m.n = n;
        m.rowPtr = rowPtr;
        m.colIndex = cols;
        m.values = diagValues.clone();
        return m;
    }
}
