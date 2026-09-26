package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.model.EigenPairResult;
import com.github.guoyyit.sparseeigensolverjava.model.EigenRequest;
import com.github.guoyyit.sparseeigensolverjava.model.EigenResponse;
import com.github.guoyyit.sparseeigensolverjava.model.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.service.EigenSolverService;
import com.github.guoyyit.sparseeigensolverjava.service.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "solver.snapshot-dir=target/test-snapshots",
        "solver.max-concurrency=4"
})
class EigenSolverServiceTest {

    @Autowired
    private EigenSolverService service;

    @Autowired
    private SnapshotStore snapshotStore;

    @BeforeEach
    @AfterEach
    void cleanSnapshots() throws Exception {
        Path dir = Path.of("target/test-snapshots");
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

    private EigenRequest laplacian(int n, String id, long seed, int nev) {
        TestCsrDsl.Matrix m = TestCsrDsl.laplacian(n);
        EigenRequest req = new EigenRequest();
        req.requestId = id;
        req.dimension = m.n;
        req.rowPtr = m.rowPtr;
        req.colIndex = m.colIndex;
        req.values = m.values;
        req.seed = seed;
        req.nev = nev;
        req.tolerance = 1e-10;
        req.maxIterations = n;
        req.timeLimitMs = 30_000L;
        req.snapshotIntervalMs = 1000L;
        return req;
    }

    @Test
    void solvesSmallestEigenvaluesOfLaplacian() {
        int n = 40;
        EigenResponse r = service.submit(laplacian(n, "lap-basic", 7L, 2), true);

        assertEquals(SolveStatus.CONVERGED, r.status,
                () -> "status=" + r.status + " code=" + r.errorCode + " msg=" + r.message);
        assertEquals(2, r.results.size());
        for (int k = 0; k < 2; k++) {
            double expected = 2.0 - 2.0 * Math.cos((k + 1) * Math.PI / (n + 1));
            assertEquals(expected, r.results.get(k).eigenvalue, 1e-8,
                    "eigenvalue " + k);
            assertTrue(r.results.get(k).residual <= 1e-9,
                    "residual " + r.results.get(k).residual);
            assertTrue(r.results.get(k).converged);
        }
        // documentation fields present
        assertNotNull(r.signConvention);
        assertNotNull(r.normalization);
        assertNotNull(r.algorithm);
    }

    @Test
    void eigenvectorsAreUnitNormWithFixedSign() {
        int n = 30;
        EigenResponse r = service.submit(laplacian(n, "lap-sign", 11L, 3), true);
        assertEquals(SolveStatus.CONVERGED, r.status, r.message);
        for (EigenPairResult p : r.results) {
            double norm = 0;
            int pivot = 0;
            double best = Math.abs(p.eigenvector[0]);
            for (int i = 0; i < p.eigenvector.length; i++) {
                norm += p.eigenvector[i] * p.eigenvector[i];
                if (Math.abs(p.eigenvector[i]) > best) {
                    best = Math.abs(p.eigenvector[i]);
                    pivot = i;
                }
            }
            assertEquals(1.0, norm, 1e-12, "unit 2-norm");
            assertTrue(p.eigenvector[pivot] >= 0.0, "largest component non-negative");
        }
    }

    @Test
    void identicalInputAndSeedProduceBitIdenticalResults() {
        EigenResponse a = service.submit(laplacian(50, "det-a", 99L, 3), true);
        snapshotStore.clearState("det-a");
        EigenResponse b = service.submit(laplacian(50, "det-a", 99L, 3), true);
        assertEquals(SolveStatus.CONVERGED, a.status, a.message);
        assertEquals(SolveStatus.CONVERGED, b.status, b.message);
        for (int k = 0; k < 3; k++) {
            assertEquals(a.results.get(k).eigenvalue, b.results.get(k).eigenvalue, 0.0);
            assertArrayEquals(a.results.get(k).eigenvector,
                    b.results.get(k).eigenvector, 0.0);
        }
    }

    @Test
    void differentSeedsCanChangeBasisButStillConverge() {
        EigenResponse a = service.submit(laplacian(35, "seed-a", 1L, 2), true);
        snapshotStore.clearState("seed-a");
        EigenResponse b = service.submit(laplacian(35, "seed-a", 2L, 2), true);
        assertEquals(SolveStatus.CONVERGED, a.status);
        assertEquals(SolveStatus.CONVERGED, b.status);
        // eigenvalues always equal even if vector phases differed before sign fix
        for (int k = 0; k < 2; k++) {
            assertEquals(a.results.get(k).eigenvalue, b.results.get(k).eigenvalue, 1e-10);
        }
    }

    @Test
    void intervalFilterRestrictsReturnedEigenvalues() {
        int n = 40;
        EigenRequest req = laplacian(n, "interval", 3L, 1);
        req.intervalLower = 2.0;
        req.intervalUpper = 2.5;
        EigenResponse r = service.submit(req, true);
        assertEquals(SolveStatus.CONVERGED, r.status, r.message);
        assertEquals(1, r.results.size());
        double lambda = r.results.get(0).eigenvalue;
        assertTrue(lambda >= 2.0 - 1e-9 && lambda <= 2.5 + 1e-9,
                "lambda=" + lambda);
    }

    @Test
    void repeatsDoNotRecomputeJournaledResult() {
        EigenResponse first = service.submit(laplacian(20, "journal", 5L, 1), true);
        assertEquals(SolveStatus.CONVERGED, first.status);
        // immediate second submit with same id returns the journaled snapshot
        EigenResponse second = service.submit(laplacian(20, "journal", 5L, 1), true);
        assertEquals(SolveStatus.CONVERGED, second.status);
        assertEquals(first.results.get(0).eigenvalue,
                second.results.get(0).eigenvalue, 0.0);
        assertArrayEquals(first.results.get(0).eigenvector,
                second.results.get(0).eigenvector, 0.0);
        assertEquals(first.iterations, second.iterations);
    }
}
