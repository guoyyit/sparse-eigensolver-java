package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidator;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatrixValidatorTest {

    private final MatrixValidator validator = new MatrixValidator();

    // diagonal 3x3: diag (2,3,4)
    private CsrMatrix diagMatrix() {
        return validator.validate(3,
                new int[] {0, 1, 2, 3},
                new int[] {0, 1, 2},
                new double[] {2, 3, 4}, 0);
    }

    @Test
    void validDiagonalMatrixAndSpmv() {
        CsrMatrix a = diagMatrix();
        double[] y = new double[3];
        a.multiply(new double[] {1, 2, 3}, y);
        assertArrayEquals(new double[] {2, 6, 12}, y, 1e-15);
    }

    @Test
    void symmetricOffDiagonalSpmvUsesBothTriangles() {
        // [[2,1],[1,3]] upper triangle only
        CsrMatrix a = validator.validate(2,
                new int[] {0, 2, 3},
                new int[] {0, 1, 1},
                new double[] {2, 1, 3}, 0);
        double[] y = new double[2];
        a.multiply(new double[] {1, 2}, y);
        assertArrayEquals(new double[] {4, 7}, y, 1e-15);
    }

    @Test
    void rejectsNonPositiveDimension() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(0, new int[] {0}, new int[0],
                        new double[0], 0));
        assertEquals(ErrorCode.INVALID_DIMENSION, ex.getErrorCode());
    }

    @Test
    void rejectsDimensionMismatchRowPtr() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(3, new int[] {0, 1, 2},
                        new int[] {0, 1}, new double[] {1, 1}, 0));
        assertEquals(ErrorCode.INVALID_DIMENSION, ex.getErrorCode());
    }

    @Test
    void rejectsMalformedPointer() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2, new int[] {1, 2, 3},
                        new int[] {0, 1, 2}, new double[] {1, 1, 1}, 0));
        assertEquals(ErrorCode.INVALID_POINTER, ex.getErrorCode());
    }

    @Test
    void rejectsDuplicateIndex() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(3,
                        new int[] {0, 2, 3, 4},
                        new int[] {0, 0, 1, 2},
                        new double[] {1, 1, 1, 1}, 0));
        assertEquals(ErrorCode.DUPLICATE_INDEX, ex.getErrorCode());
    }

    @Test
    void rejectsIndexOutOfRange() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {5, 1},
                        new double[] {1, 1}, 0));
        assertEquals(ErrorCode.INDEX_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Test
    void rejectsNonFiniteValue() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {0, 1},
                        new double[] {Double.NaN, 1}, 0));
        assertEquals(ErrorCode.NON_FINITE_VALUE, ex.getErrorCode());
    }

    @Test
    void rejectsInfiniteValue() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {0, 1},
                        new double[] {1, Double.POSITIVE_INFINITY}, 0));
        assertEquals(ErrorCode.NON_FINITE_VALUE, ex.getErrorCode());
    }

    @Test
    void rejectsBelowDiagonalAsNonSymmetricUpperTriangle() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {0, 0},
                        new double[] {1, 1}, 0));
        assertEquals(ErrorCode.NON_SYMMETRIC_UPPER_TRIANGLE, ex.getErrorCode());
    }

    @Test
    void rejectsWhenNnzLimitExceeded() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {0, 1},
                        new double[] {1, 1}, 1));
        assertEquals(ErrorCode.MATRIX_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void rejectsLengthMismatch() {
        SolverException ex = assertThrows(SolverException.class,
                () -> validator.validate(2,
                        new int[] {0, 1, 2},
                        new int[] {0, 1},
                        new double[] {1, 1, 1}, 0));
        assertEquals(ErrorCode.INVALID_POINTER, ex.getErrorCode());
    }
}
