package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core solver sanity: known spectra, determinism, non-convergence, degeneracy.
 */
class LanczosSolverTest {

    /** 1D Laplacian (2 on diagonal, -1 on off-diagonals), upper triangle CSR. */
    static CsrMatrix laplacian1D(int n) {
        List<Integer> rowPtr = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        rowPtr.add(0);
        for (int i = 0; i < n; i++) {
            cols.add(i);
            vals.add(2.0);
            if (i + 1 < n) {
                cols.add(i + 1);
                vals.add(-1.0);
            }
            rowPtr.add(cols.size());
        }
        return new CsrMatrix(n,
                rowPtr.stream().mapToInt(Integer::intValue).toArray(),
                cols.stream().mapToInt(Integer::intValue).toArray(),
                vals.stream().mapToDouble(Double::doubleValue).toArray());
    }

    static double laplacianEigenvalue(int k, int n) {
        return 2.0 - 2.0 * Math.cos(k * Math.PI / (n + 1));
    }

    @Test
    void solvesSmallestEigenpairsOfLaplacian() {
        int n = 200;
        CsrMatrix a = laplacian1D(n);
        MatrixValidator.validate(a);
        SolverOptions opt = new SolverOptions(4, 1e-10, 200, null, null, 7L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.CONVERGED, r.status(), r.message());
        assertEquals(4, r.eigenpairs().size());
        for (int i = 0; i < 4; i++) {
            double expected = laplacianEigenvalue(i + 1, n);
            assertEquals(expected, r.eigenpairs().get(i).eigenvalue(), 1e-6,
                    "eigenvalue " + i);
            assertTrue(r.eigenpairs().get(i).residualNorm() < 1e-8,
                    "residual " + i + ": " + r.eigenpairs().get(i).residualNorm());
            // unit norm
            double norm = 0;
            for (double v : r.eigenpairs().get(i).eigenvector()) {
                norm += v * v;
            }
            assertEquals(1.0, Math.sqrt(norm), 1e-10);
        }
    }

    @Test
    void diagonalMatrixRecoveredExactly() {
        int n = 50;
        int[] rowPtr = new int[n + 1];
        int[] cols = new int[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            rowPtr[i] = i;
            cols[i] = i;
            vals[i] = i + 1;
        }
        rowPtr[n] = n;
        CsrMatrix a = new CsrMatrix(n, rowPtr, cols, vals);
        SolverOptions opt = new SolverOptions(3, 1e-12, 100, null, null, 1L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.CONVERGED, r.status(), r.message());
        assertEquals(1.0, r.eigenpairs().get(0).eigenvalue(), 1e-10);
        assertEquals(2.0, r.eigenpairs().get(1).eigenvalue(), 1e-10);
        assertEquals(3.0, r.eigenpairs().get(2).eigenvalue(), 1e-10);
    }

    @Test
    void deterministicForSameSeed() {
        CsrMatrix a = laplacian1D(120);
        SolverOptions opt = new SolverOptions(3, 1e-9, 100, null, null, 99L, 0);
        EigenResult r1 = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        EigenResult r2 = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.CONVERGED, r1.status());
        assertEquals(r1.totalIterations(), r2.totalIterations());
        for (int i = 0; i < r1.eigenpairs().size(); i++) {
            assertEquals(r1.eigenpairs().get(i).eigenvalue(), r2.eigenpairs().get(i).eigenvalue(), 0.0);
            assertArrayEquals(r1.eigenpairs().get(i).eigenvector(),
                    r2.eigenpairs().get(i).eigenvector(), 0.0);
        }
    }

    @Test
    void reportsNotConvergedWhenIterationBudgetExhausted() {
        CsrMatrix a = laplacian1D(500);
        SolverOptions opt = new SolverOptions(6, 1e-14, 1, null, null, 5L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.FAILED, r.status());
        assertEquals(ErrorCode.NOT_CONVERGED, r.errorCode());
        assertTrue(r.eigenpairs().isEmpty(), "no success-wrapped approximations");
        assertFalse(r.history().isEmpty(), "diagnostics retained");
    }

    @Test
    void detectsNumericalDegeneracyOnOverflow() {
        int n = 30;
        int[] rowPtr = new int[n + 1];
        int[] cols = new int[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            rowPtr[i] = i;
            cols[i] = i;
            vals[i] = 1e308; // SpMV overflow -> Inf in recurrence
        }
        rowPtr[n] = n;
        CsrMatrix a = new CsrMatrix(n, rowPtr, cols, vals);
        SolverOptions opt = new SolverOptions(2, 1e-8, 50, null, null, 3L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.FAILED, r.status());
        assertEquals(ErrorCode.NUMERICAL_DEGENERACY, r.errorCode());
        assertTrue(r.eigenpairs().isEmpty());
    }

    @Test
    void intervalFilterSelectsOutputWindow() {
        int n = 60;
        int[] rowPtr = new int[n + 1];
        int[] cols = new int[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            rowPtr[i] = i;
            cols[i] = i;
            vals[i] = i + 1;
        }
        rowPtr[n] = n;
        CsrMatrix a = new CsrMatrix(n, rowPtr, cols, vals);
        SolverOptions opt = new SolverOptions(5, 1e-12, 100, 2.5, 4.5, 1L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).solve(null, "t");
        assertEquals(SolveStatus.CONVERGED, r.status(), r.message());
        assertEquals(2, r.eigenpairs().size());
        assertEquals(3.0, r.eigenpairs().get(0).eigenvalue(), 1e-9);
        assertEquals(4.0, r.eigenpairs().get(1).eigenvalue(), 1e-9);
    }

    @Test
    void cancellationStopsIteration() {
        CsrMatrix a = laplacian1D(300);
        SolverOptions opt = new SolverOptions(5, 1e-12, 1000, null, null, 5L, 0);
        EigenResult r = new LanczosSolver(a, opt, () -> true, 0).solve(null, "t");
        assertEquals(SolveStatus.CANCELLED, r.status());
        assertEquals(ErrorCode.CANCELLED, r.errorCode());
    }
}
