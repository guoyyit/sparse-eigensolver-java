package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.algo.LanczosSolver;
import com.github.guoyyit.sparseeigensolverjava.algo.LanczosState;
import com.github.guoyyit.sparseeigensolverjava.algo.TridiagonalEigensolver;
import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidator;
import com.github.guoyyit.sparseeigensolverjava.model.EigenRequest;
import com.github.guoyyit.sparseeigensolverjava.model.EigenResponse;
import com.github.guoyyit.sparseeigensolverjava.model.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.service.EigenSolverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numerical-degeneration scenarios: a Krylov invariant subspace encountered
 * before enough Ritz pairs converged, and clustered eigenvalues. Such cases
 * must be surfaced as explicit failures, never as fake successful results.
 */
@SpringBootTest(properties = {
        "solver.snapshot-dir=target/test-snapshots-deg",
        "solver.max-dimension=2000"
})
class NumericalDegeneracyTest {

    @Autowired
    private EigenSolverService service;

    private EigenRequest request(String id, double[] diag, int nev, double tol) {
        TestCsrDsl.Matrix m = TestCsrDsl.diagonal(diag);
        EigenRequest r = new EigenRequest();
        r.requestId = id;
        r.dimension = m.n;
        r.rowPtr = m.rowPtr;
        r.colIndex = m.colIndex;
        r.values = m.values;
        r.seed = 1L;
        r.nev = nev;
        r.tolerance = tol;
        r.maxIterations = m.n;
        r.timeLimitMs = 30_000L;
        return r;
    }

    @Test
    void diagonalMatrixConvergesItsSmallestEigenpairsExactly() {
        EigenResponse r = service.submit(
                request("deg-ok", new double[] {1, 2, 3, 4, 5}, 3, 1e-8), true);
        assertEquals(SolveStatus.CONVERGED, r.status, r.message);
        assertEquals(3, r.results.size());
        for (int k = 0; k < 3; k++) {
            assertEquals(k + 1, r.results.get(k).eigenvalue, 1e-9);
            assertTrue(r.results.get(k).residual <= 1e-8);
        }
    }

    @Test
    void clusteredEigenvaluesStillResolveExtremalPairsOrFailHonestly() {
        // tight cluster in the interior; extremal pair requested with a very tight
        // tolerance. Either it resolves (all converged) or it must report failure
        // with diagnostics - never a wrapped success.
        double[] diag = new double[20];
        diag[0] = 0.0;
        for (int i = 1; i < 19; i++) {
            diag[i] = 1.0 + 1e-9 * i; // tight interior cluster
        }
        diag[19] = 5.0;
        EigenResponse r = service.submit(request("deg-cluster", diag, 1, 1e-13), true);
        if (r.status == SolveStatus.CONVERGED) {
            assertEquals(1, r.results.size());
            assertTrue(r.results.get(0).residual <= 1e-13,
                    "converged result must meet tolerance");
        } else {
            assertEquals(SolveStatus.FAILED, r.status);
            assertTrue(r.errorCode.equals("NOT_CONVERGED")
                            || r.errorCode.equals("NUMERICAL_BREAKDOWN"),
                    "unexpected code " + r.errorCode);
        }
    }

    @Test
    void directLanczosOnDiagonalHitsExactInvariantSubspaceForAllPairs() {
        // A diagonal matrix with all entries distinct still spans the full space
        // via the seed-driven start vector; requesting all n pairs converges.
        MatrixValidator mv = new MatrixValidator();
        TestCsrDsl.Matrix mb = TestCsrDsl.diagonal(2, 4, 6, 8);
        CsrMatrix a = mv.validate(4, mb.rowPtr, mb.colIndex, mb.values, 0);
        LanczosSolver solver = new LanczosSolver();
        LanczosState st = solver.init("direct", a, 123L, 4, 1e-9, null, null, 4);
        solver.run(st, a, new LanczosSolver.Control() {
            public boolean isCancelled() {
                return false;
            }

            public long deadlineMs() {
                return Long.MAX_VALUE;
            }

            public void onStepComplete(LanczosState s) {
            }
        });
        // full diagonal must deliver all 4 exact eigenvalues when subspace closes
        assertEquals("CONVERGED", st.status, st.message);
        var pairs = solver.assembleResults(st);
        assertEquals(4, pairs.size());
        double[] expected = {2, 4, 6, 8};
        for (int k = 0; k < 4; k++) {
            assertEquals(expected[k], pairs.get(k).eigenvalue, 1e-8);
        }
    }

    @Test
    void tridiagonalSolverRejectsNonFiniteInputAsBreakdown() {
        com.github.guoyyit.sparseeigensolverjava.core.SolverException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.github.guoyyit.sparseeigensolverjava.core.SolverException.class,
                        () -> TridiagonalEigensolver.eigenvalues(
                                new double[] {1, Double.NaN}, new double[] {0}));
        assertEquals(
                com.github.guoyyit.sparseeigensolverjava.core.ErrorCode.NUMERICAL_BREAKDOWN,
                ex.getErrorCode());
    }
}
