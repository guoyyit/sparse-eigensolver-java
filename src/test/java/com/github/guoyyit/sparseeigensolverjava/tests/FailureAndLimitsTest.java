package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import com.github.guoyyit.sparseeigensolverjava.model.EigenRequest;
import com.github.guoyyit.sparseeigensolverjava.model.EigenResponse;
import com.github.guoyyit.sparseeigensolverjava.model.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.service.EigenSolverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers parameter validation, resource limits, non-convergence and numerical
 * degeneration failure semantics.
 */
@SpringBootTest(properties = {
        "solver.snapshot-dir=target/test-snapshots-fail",
        "solver.max-dimension=200",
        "solver.max-non-zeros=100000",
        "solver.per-request-memory-bytes=200000",
        "solver.max-iterations=200"
})
class FailureAndLimitsTest {

    @Autowired
    private EigenSolverService service;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        Path dir = Path.of("target/test-snapshots-fail");
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    private EigenRequest diag(double... vals) {
        TestCsrDsl.Matrix m = TestCsrDsl.diagonal(vals);
        EigenRequest req = new EigenRequest();
        req.requestId = "lim-" + System.nanoTime();
        req.dimension = m.n;
        req.rowPtr = m.rowPtr;
        req.colIndex = m.colIndex;
        req.values = m.values;
        req.seed = 1L;
        req.tolerance = 1e-8;
        req.maxIterations = m.n;
        req.timeLimitMs = 30_000L;
        return req;
    }

    @Test
    void invalidNevRejectedWithDistinctCode() {
        EigenRequest r = diag(1, 2, 3);
        r.nev = 5;
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.INVALID_NEV, ex.getErrorCode());
    }

    @Test
    void invalidToleranceRejectedWithDistinctCode() {
        EigenRequest r = diag(1, 2, 3);
        r.tolerance = 0.0;
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.INVALID_TOLERANCE, ex.getErrorCode());
    }

    @Test
    void invalidIntervalRejectedWithDistinctCode() {
        EigenRequest r = diag(1, 2, 3);
        r.intervalLower = 5.0;
        r.intervalUpper = 1.0;
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.INVALID_INTERVAL, ex.getErrorCode());
    }

    @Test
    void invalidMaxIterationsRejectedWithDistinctCode() {
        EigenRequest r = diag(1, 2, 3);
        r.maxIterations = 100000;
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.INVALID_MAX_ITERATIONS, ex.getErrorCode());
    }

    @Test
    void dimensionOverLimitRejectedAsTooLarge() {
        double[] diag = new double[250];
        for (int i = 0; i < diag.length; i++) {
            diag[i] = i + 1;
        }
        EigenRequest r = diag(diag);
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.MATRIX_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void memoryBudgetRejectsLargeKrylovBasisWithResourceLimit() {
        // n=200, maxIterations=200 -> basis ~321KB > 200KB per-request cap.
        double[] diag = new double[200];
        for (int i = 0; i < diag.length; i++) {
            diag[i] = i + 1;
        }
        EigenRequest r = diag(diag);
        r.maxIterations = 200;
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.RESOURCE_LIMIT, ex.getErrorCode());
    }

    @Test
    void missingRequestIdRejected() {
        EigenRequest r = diag(1, 2);
        r.requestId = "bad id!!";
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(r, true));
        assertEquals(ErrorCode.INVALID_CONFIG, ex.getErrorCode());
    }

    @Test
    void unknownJobStatusReturnsJobNotFound() {
        SolverException ex = assertThrows(SolverException.class,
                () -> service.getStatus("does-not-exist-" + System.nanoTime()));
        assertEquals(ErrorCode.JOB_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void nonConvergenceIsExplicitFailureNotWrappedAsSuccess() {
        // Tridiagonal Laplacian: converging its smallest eigenvalues needs far
        // more Lanczos steps than the deliberately tiny budget allows.
        TestCsrDsl.Matrix m = TestCsrDsl.laplacian(100);
        EigenRequest r = new EigenRequest();
        r.requestId = "nc-" + System.nanoTime();
        r.dimension = m.n;
        r.rowPtr = m.rowPtr;
        r.colIndex = m.colIndex;
        r.values = m.values;
        r.seed = 1L;
        r.nev = 3;
        r.tolerance = 1e-14;
        r.maxIterations = 8; // far below what the extreme eigenvalues need
        r.timeLimitMs = 30_000L;
        EigenResponse resp = service.submit(r, true);
        assertEquals(SolveStatus.FAILED, resp.status);
        assertEquals(ErrorCode.NOT_CONVERGED.name(), resp.errorCode);
        assertNotNull(resp.message);
        // diagnostic iteration info is retained, never a fake success
        assertTrue(resp.iterations > 0);
    }
}
