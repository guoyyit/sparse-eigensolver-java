package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.algo.TridiagonalEigensolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TridiagonalEigensolverTest {

    @Test
    void oneByOne() {
        TridiagonalEigensolver.Result r =
                TridiagonalEigensolver.solve(new double[] {3.5}, new double[1]);
        assertEquals(3.5, r.eigenvalues[0], 1e-15);
        assertEquals(1.0, r.eigenvectors[0][0], 1e-15);
    }

    @Test
    void diagonalMatrixEigenvalues() {
        int m = 5;
        double[] diag = new double[m];
        for (int i = 0; i < m; i++) {
            diag[i] = i + 1; // 1..5
        }
        TridiagonalEigensolver.Result r =
                TridiagonalEigensolver.solve(diag, new double[m]);
        for (int i = 0; i < m; i++) {
            assertEquals(i + 1, r.eigenvalues[i], 1e-12);
        }
    }

    @Test
    void eigenpairsSatisfyTvEqualsThetaVAndAreOrthonormal() {
        // tridiagonal with diag 2, offdiag -1 (discrete Laplacian, size 6)
        int m = 6;
        double[] diag = new double[m];
        double[] off = new double[m];
        java.util.Arrays.fill(diag, 2.0);
        for (int i = 0; i < m - 1; i++) {
            off[i] = -1.0;
        }
        TridiagonalEigensolver.Result r = TridiagonalEigensolver.solve(diag, off);

        for (int i = 0; i < m; i++) {
            // ascending order
            if (i > 0) {
                assertTrue(r.eigenvalues[i] >= r.eigenvalues[i - 1]);
            }
            double theta = r.eigenvalues[i];
            double[] v = r.eigenvectors[i];
            // T v = theta v
            for (int k = 0; k < m; k++) {
                double tv = diag[k] * v[k];
                if (k > 0) {
                    tv += off[k - 1] * v[k - 1];
                }
                if (k < m - 1) {
                    tv += off[k] * v[k + 1];
                }
                assertEquals(theta * v[k], tv, 1e-10);
            }
            // unit norm
            double nrm = 0;
            for (double x : v) {
                nrm += x * x;
            }
            assertEquals(1.0, nrm, 1e-12);
            // mutual orthogonality
            for (int j = 0; j < i; j++) {
                double dot = 0;
                for (int k = 0; k < m; k++) {
                    dot += v[k] * r.eigenvectors[j][k];
                }
                assertEquals(0.0, dot, 1e-10);
            }
        }
        // known smallest eigenvalue 2 - 2 cos(pi/7) ~ 0.198
        assertEquals(2.0 - 2.0 * Math.cos(Math.PI / 7.0), r.eigenvalues[0], 1e-9);
    }

    @Test
    void deterministicAcrossCalls() {
        double[] diag = {2, 2, 2, 2};
        double[] off = {-1, -1, -1, 0};
        TridiagonalEigensolver.Result a = TridiagonalEigensolver.solve(diag, off);
        TridiagonalEigensolver.Result b = TridiagonalEigensolver.solve(diag, off);
        for (int i = 0; i < 4; i++) {
            assertEquals(a.eigenvalues[i], b.eigenvalues[i], 0.0);
            for (int j = 0; j < 4; j++) {
                assertEquals(a.eigenvectors[i][j], b.eigenvectors[i][j], 0.0);
            }
        }
    }
}
