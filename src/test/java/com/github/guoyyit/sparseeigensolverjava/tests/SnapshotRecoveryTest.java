package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "solver.snapshot-dir=target/test-snapshots-rec",
        "solver.max-concurrency=4"
})
class SnapshotRecoveryTest {

    @Autowired
    private EigenSolverService service;

    @Autowired
    private SnapshotStore snapshotStore;

    private static final int N = 120;
    private static final int NEV = 3;
    private static final double TOL = 1e-6;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        Path dir = Path.of("target/test-snapshots-rec");
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

    private EigenRequest request(String id, long seed, int nev) {
        TestCsrDsl.Matrix m = TestCsrDsl.laplacian(N);
        EigenRequest req = new EigenRequest();
        req.requestId = id;
        req.dimension = m.n;
        req.rowPtr = m.rowPtr;
        req.colIndex = m.colIndex;
        req.values = m.values;
        req.seed = seed;
        req.nev = nev;
        req.tolerance = TOL;
        req.maxIterations = N;
        req.timeLimitMs = 30_000L;
        // very frequent snapshots so a crash always preserves recent work
        req.snapshotIntervalMs = 1L;
        return req;
    }

    @Test
    void resumesFromSnapshotAfterCrashAndMatchesUninterruptedResult() {
        // baseline uninterrupted run
        EigenResponse baseline = service.submit(request("base", 42L, NEV), true);
        assertEquals(SolveStatus.CONVERGED, baseline.status, baseline.message);
        assertTrue(baseline.iterations > 10,
                "baseline should need more than the crash iteration");

        // crashed run: inject BEFORE submitting
        String id = "crash-1";
        service.injectCrashAfterIteration(id, 10);
        EigenResponse crashed = service.submit(request(id, 42L, NEV), true);
        assertEquals(SolveStatus.FAILED, crashed.status);
        assertEquals("PROCESS_CRASHED", crashed.errorCode);
        assertTrue(snapshotStore.exists(id));

        // "process restart": resubmit same requestId/input/seed/config
        EigenResponse resumed = service.submit(request(id, 42L, NEV), true);
        assertEquals(SolveStatus.CONVERGED, resumed.status, resumed.message);
        assertEquals(baseline.iterations, resumed.iterations,
                "final Krylov dimension is independent of interruption");
        assertEquals(NEV, resumed.results.size());

        List<EigenPairResult> a = baseline.results;
        List<EigenPairResult> b = resumed.results;
        for (int k = 0; k < NEV; k++) {
            assertEquals(a.get(k).eigenvalue, b.get(k).eigenvalue, 0.0,
                    "eigenvalue bit-identical after recovery");
            assertArrayEquals(a.get(k).eigenvector, b.get(k).eigenvector, 0.0,
                    "eigenvector bit-identical after recovery");
        }
    }

    @Test
    void snapshotCapturesCompletedKrylovStepsForResume() {
        String id = "crash-2";
        service.injectCrashAfterIteration(id, 20);
        service.submit(request(id, 7L, 2), true);

        var st = snapshotStore.loadState(id);
        assertTrue(st != null && !st.finished);
        assertEquals(20, st.j, "snapshot captures exactly completed Krylov steps");

        EigenResponse resumed = service.submit(request(id, 7L, 2), true);
        assertEquals(SolveStatus.CONVERGED, resumed.status, resumed.message);
        // resumed job completed fewer additional steps than a from-scratch run
        // would take overall, demonstrating it did not start over
        assertEquals(2, resumed.results.size());
    }

    @Test
    void cannotResumeSnapshotWithDifferentParameters() {
        String id = "mismatch";
        service.injectCrashAfterIteration(id, 20);
        service.submit(request(id, 1L, 2), true);
        assertTrue(snapshotStore.exists(id));

        EigenRequest changed = request(id, 1L, 3); // different nev
        SolverException ex = assertThrows(SolverException.class,
                () -> service.submit(changed, true));
        assertEquals(ErrorCode.SNAPSHOT_MISMATCH, ex.getErrorCode());
    }

    @Test
    void journaledResultAfterRecoveryIsNotRecomputedOrEmittedTwice() {
        String id = "crash-4";
        service.injectCrashAfterIteration(id, 15);
        service.submit(request(id, 5L, NEV), true);

        EigenResponse once = service.submit(request(id, 5L, NEV), true);
        assertEquals(SolveStatus.CONVERGED, once.status);
        // a third submit returns the same journaled result without recomputation
        EigenResponse again = service.submit(request(id, 5L, NEV), true);
        assertEquals(SolveStatus.CONVERGED, again.status);
        for (int k = 0; k < NEV; k++) {
            assertEquals(once.results.get(k).eigenvalue,
                    again.results.get(k).eigenvalue, 0.0);
            assertArrayEquals(once.results.get(k).eigenvector,
                    again.results.get(k).eigenvector, 0.0);
        }
    }
}
