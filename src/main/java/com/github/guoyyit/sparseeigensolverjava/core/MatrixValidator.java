package com.github.guoyyit.sparseeigensolverjava.core;

import org.springframework.stereotype.Component;

/**
 * Validates raw CSR arrays describing a real symmetric matrix and produces an
 * immutable {@link CsrMatrix} containing only the upper triangle.
 *
 * <p>Every illegal-input category raises a {@link SolverException} with a
 * distinct {@link ErrorCode} and a diagnostic message:
 * <ul>
 *   <li>{@code INVALID_DIMENSION} - non-positive dimension</li>
 *   <li>{@code INVALID_POINTER} - missing/malformed rowPtr</li>
 *   <li>{@code INDEX_OUT_OF_RANGE} - column index outside {@code [0,n)}</li>
 *   <li>{@code NON_SYMMETRIC_UPPER_TRIANGLE} - entry below the diagonal</li>
 *   <li>{@code DUPLICATE_INDEX} - repeated column index in a row</li>
 *   <li>{@code NON_FINITE_VALUE} - NaN or infinite value</li>
 *   <li>{@code MATRIX_TOO_LARGE} - configured nnz limit exceeded</li>
 * </ul>
 */
@Component
public class MatrixValidator {

    /**
     * @param maxNnz inclusive upper bound on stored entries; {@code <= 0} means
     *               no limit is enforced by the validator
     */
    public CsrMatrix validate(int n, int[] rowPtr, int[] colIndex, double[] values,
                              long maxNnz) {
        if (n <= 0) {
            throw new SolverException(ErrorCode.INVALID_DIMENSION,
                    "dimension must be positive, got n=" + n);
        }
        if (rowPtr == null || colIndex == null || values == null) {
            throw new SolverException(ErrorCode.INVALID_POINTER,
                    "rowPtr, colIndex and values must all be provided");
        }
        if (rowPtr.length != n + 1) {
            throw new SolverException(ErrorCode.INVALID_DIMENSION,
                    "rowPtr length must be n+1 = " + (n + 1) + ", got "
                            + rowPtr.length);
        }
        if (colIndex.length != values.length) {
            throw new SolverException(ErrorCode.INVALID_POINTER,
                    "colIndex/values length mismatch: " + colIndex.length + " vs "
                            + values.length);
        }
        if (rowPtr[0] != 0) {
            throw new SolverException(ErrorCode.INVALID_POINTER,
                    "rowPtr[0] must be 0, got " + rowPtr[0]);
        }
        int nnz = colIndex.length;
        if (rowPtr[n] != nnz) {
            throw new SolverException(ErrorCode.INVALID_POINTER,
                    "rowPtr[n] must equal number of entries " + nnz + ", got "
                            + rowPtr[n]);
        }
        for (int i = 0; i < n; i++) {
            if (rowPtr[i] > rowPtr[i + 1]) {
                throw new SolverException(ErrorCode.INVALID_POINTER,
                        "rowPtr must be non-decreasing at row " + i);
            }
        }
        if (maxNnz > 0 && nnz > maxNnz) {
            throw new SolverException(ErrorCode.MATRIX_TOO_LARGE,
                    "matrix has " + nnz + " stored entries, limit is " + maxNnz);
        }

        for (int i = 0; i < n; i++) {
            int start = rowPtr[i];
            int end = rowPtr[i + 1];
            int prev = -1;
            for (int k = start; k < end; k++) {
                int j = colIndex[k];
                if (j < 0 || j >= n) {
                    throw new SolverException(ErrorCode.INDEX_OUT_OF_RANGE,
                            "column index " + j + " at row " + i
                                    + " is outside [0," + n + ")");
                }
                if (j < i) {
                    throw new SolverException(
                            ErrorCode.NON_SYMMETRIC_UPPER_TRIANGLE,
                            "entry (" + i + "," + j
                                    + ") is below the diagonal; only upper triangle "
                                    + "(column >= row) entries are accepted");
                }
                if (j == prev || (k > start && j <= prev)) {
                    throw new SolverException(ErrorCode.DUPLICATE_INDEX,
                            "duplicate or unsorted column index " + j + " in row "
                                    + i);
                }
                prev = j;
                double v = values[k];
                if (!Double.isFinite(v)) {
                    throw new SolverException(ErrorCode.NON_FINITE_VALUE,
                            "non-finite value " + v + " at entry (" + i + "," + j
                                    + ")");
                }
            }
        }
        return new CsrMatrix(n, rowPtr, colIndex, values);
    }
}
