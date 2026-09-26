package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidationException;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidator;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each illegal-input category must map to its own distinguishable error code.
 */
class MatrixValidatorTest {

    private static CsrMatrix valid() {
        // 3x3 identity in CSR upper-triangle form
        return new CsrMatrix(3, new int[]{0, 1, 2, 3}, new int[]{0, 1, 2},
                new double[]{1, 1, 1});
    }

    private static ErrorCode expectReject(CsrMatrix m) {
        MatrixValidationException e = assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m));
        return e.code();
    }

    @Test
    void acceptsValidMatrix() {
        assertDoesNotThrow(() -> MatrixValidator.validate(valid()));
    }

    @Test
    void rejectsRowPtrLengthMismatch() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(3, new int[]{0, 1, 2}, new int[]{0, 1, 2}, new double[]{1, 1, 1})));
    }

    @Test
    void rejectsValuesLengthMismatch() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(3, new int[]{0, 1, 2, 3}, new int[]{0, 1, 2}, new double[]{1, 1})));
    }

    @Test
    void rejectsRowPtrNotStartingAtZero() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(3, new int[]{1, 1, 2, 3}, new int[]{0, 1, 2}, new double[]{1, 1, 1})));
    }

    @Test
    void rejectsRowPtrDecreasing() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(3, new int[]{0, 2, 1, 3}, new int[]{0, 1, 2}, new double[]{1, 1, 1})));
    }

    @Test
    void rejectsRowPtrEndMismatch() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(3, new int[]{0, 1, 2, 5}, new int[]{0, 1, 2}, new double[]{1, 1, 1})));
    }

    @Test
    void rejectsNonPositiveDimension() {
        assertEquals(ErrorCode.DIMENSION_MISMATCH, expectReject(
                new CsrMatrix(0, new int[]{0}, new int[]{}, new double[]{})));
    }

    @Test
    void rejectsDuplicateIndex() {
        assertEquals(ErrorCode.DUPLICATE_INDEX, expectReject(
                new CsrMatrix(2, new int[]{0, 2, 3}, new int[]{0, 0, 1}, new double[]{1, 2, 1})));
    }

    @Test
    void rejectsNaN() {
        assertEquals(ErrorCode.NON_FINITE_VALUE, expectReject(
                new CsrMatrix(2, new int[]{0, 1, 2}, new int[]{0, 1}, new double[]{1, Double.NaN})));
    }

    @Test
    void rejectsInfinity() {
        assertEquals(ErrorCode.NON_FINITE_VALUE, expectReject(
                new CsrMatrix(2, new int[]{0, 1, 2}, new int[]{0, 1},
                        new double[]{1, Double.POSITIVE_INFINITY})));
    }

    @Test
    void rejectsLowerTriangleEntry() {
        // entry (1,0) is below the diagonal: not the symmetric upper triangle
        assertEquals(ErrorCode.ASYMMETRIC_STRUCTURE, expectReject(
                new CsrMatrix(2, new int[]{0, 1, 2}, new int[]{0, 0}, new double[]{1, 1})));
    }

    @Test
    void rejectsUnsortedColumns() {
        assertEquals(ErrorCode.ASYMMETRIC_STRUCTURE, expectReject(
                new CsrMatrix(3, new int[]{0, 2, 3, 4}, new int[]{2, 0, 1, 2},
                        new double[]{1, 1, 1, 1})));
    }

    @Test
    void rejectsColumnOutOfRange() {
        assertEquals(ErrorCode.INDEX_OUT_OF_RANGE, expectReject(
                new CsrMatrix(2, new int[]{0, 1, 2}, new int[]{0, 5}, new double[]{1, 1})));
        assertEquals(ErrorCode.INDEX_OUT_OF_RANGE, expectReject(
                new CsrMatrix(2, new int[]{0, 1, 2}, new int[]{0, -1}, new double[]{1, 1})));
    }

    @Test
    void rejectsBadParameters() {
        CsrMatrix m = valid();
        // numEigenvalues out of range
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(0, 1e-8, 10, null, null, 1, 0))).code());
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(4, 1e-8, 10, null, null, 1, 0))).code());
        // non-positive tolerance
        assertEquals(ErrorCode.NON_FINITE_VALUE, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(1, 0.0, 10, null, null, 1, 0))).code());
        assertEquals(ErrorCode.NON_FINITE_VALUE, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(1, Double.NaN, 10, null, null, 1, 0))).code());
        // non-positive maxIterations
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(1, 1e-8, 0, null, null, 1, 0))).code());
        // inverted interval
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(1, 1e-8, 10, 5.0, 1.0, 1, 0))).code());
        // non-finite interval bound
        assertEquals(ErrorCode.NON_FINITE_VALUE, assertThrows(MatrixValidationException.class,
                () -> MatrixValidator.validate(m, new SolverOptions(1, 1e-8, 10, null, Double.NaN, 1, 0))).code());
    }
}
