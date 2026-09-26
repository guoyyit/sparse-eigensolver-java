package com.github.guoyyit.sparseeigensolverjava.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates CSR symmetric input and solver options before any compute starts.
 * Each failure category maps to a distinct {@link ErrorCode}.
 */
public final class MatrixValidator {

    private MatrixValidator() {
    }

    /**
     * Structural and numeric validation of the CSR matrix.
     *
     * @throws MatrixValidationException with a specific {@link ErrorCode}
     */
    public static void validate(CsrMatrix m) {
        if (m == null) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH, "matrix is null");
        }
        int n = m.n();
        if (n <= 0) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                    "dimension must be positive, got " + n);
        }
        if (m.rowPtr() == null || m.rowPtr().length != n + 1) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                    "rowPtr length must be n+1=" + (n + 1) + ", got "
                            + (m.rowPtr() == null ? "null" : m.rowPtr().length));
        }
        if (m.colIndices() == null || m.values() == null
                || m.colIndices().length != m.values().length) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                    "colIndices and values must have equal length");
        }
        int nnz = m.values().length;
        int[] rowPtr = m.rowPtr();
        if (rowPtr[0] != 0) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                    "rowPtr[0] must be 0, got " + rowPtr[0]);
        }
        for (int i = 0; i < n; i++) {
            if (rowPtr[i + 1] < rowPtr[i]) {
                throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                        "rowPtr must be non-decreasing at row " + i);
            }
        }
        if (rowPtr[n] != nnz) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH,
                    "rowPtr[n]=" + rowPtr[n] + " does not match nnz=" + nnz);
        }

        int[] col = m.colIndices();
        double[] val = m.values();
        // Duplicate detection per row via the (row,col) pair; rows are small
        // enough that a per-row set is cheap and exact.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            seen.clear();
            int prev = -1;
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                int j = col[k];
                if (j < 0 || j >= n) {
                    throw new MatrixValidationException(ErrorCode.INDEX_OUT_OF_RANGE,
                            "column index " + j + " out of range [0," + n + ") at row " + i);
                }
                if (j < i) {
                    throw new MatrixValidationException(ErrorCode.ASYMMETRIC_STRUCTURE,
                            "lower-triangle entry (" + i + "," + j
                                    + ") stored; only the symmetric upper triangle is accepted");
                }
                if (j < prev) {
                    throw new MatrixValidationException(ErrorCode.ASYMMETRIC_STRUCTURE,
                            "column indices of row " + i + " are not sorted ascending");
                }
                if (!seen.add(j)) {
                    throw new MatrixValidationException(ErrorCode.DUPLICATE_INDEX,
                            "duplicate index (" + i + "," + j + ")");
                }
                prev = j;
                double v = val[k];
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new MatrixValidationException(ErrorCode.NON_FINITE_VALUE,
                            "non-finite value at (" + i + "," + j + ")");
                }
            }
        }
    }

    /**
     * Parameter validation against the matrix dimension.
     *
     * @throws MatrixValidationException with {@link ErrorCode#INVALID_PARAMETER}
     */
    public static void validate(CsrMatrix m, SolverOptions o) {
        validate(m);
        if (o == null) {
            throw new MatrixValidationException(ErrorCode.INVALID_PARAMETER, "options are null");
        }
        int n = m.n();
        if (o.numEigenvalues() < 1 || o.numEigenvalues() > n) {
            throw new MatrixValidationException(ErrorCode.INVALID_PARAMETER,
                    "numEigenvalues must be in [1," + n + "], got " + o.numEigenvalues());
        }
        if (Double.isNaN(o.tolerance()) || Double.isInfinite(o.tolerance()) || o.tolerance() <= 0) {
            throw new MatrixValidationException(ErrorCode.NON_FINITE_VALUE,
                    "tolerance must be finite and positive, got " + o.tolerance());
        }
        if (o.maxIterations() <= 0) {
            throw new MatrixValidationException(ErrorCode.INVALID_PARAMETER,
                    "maxIterations must be positive, got " + o.maxIterations());
        }
        if (o.intervalMin() != null && (o.intervalMin().isNaN() || o.intervalMin().isInfinite())) {
            throw new MatrixValidationException(ErrorCode.NON_FINITE_VALUE,
                    "intervalMin must be finite");
        }
        if (o.intervalMax() != null && (o.intervalMax().isNaN() || o.intervalMax().isInfinite())) {
            throw new MatrixValidationException(ErrorCode.NON_FINITE_VALUE,
                    "intervalMax must be finite");
        }
        if (o.intervalMin() != null && o.intervalMax() != null
                && o.intervalMin() > o.intervalMax()) {
            throw new MatrixValidationException(ErrorCode.INVALID_PARAMETER,
                    "intervalMin " + o.intervalMin() + " exceeds intervalMax " + o.intervalMax());
        }
        if (o.snapshotEvery() < 0) {
            throw new MatrixValidationException(ErrorCode.INVALID_PARAMETER,
                    "snapshotEvery must be >= 0, got " + o.snapshotEvery());
        }
    }
}
