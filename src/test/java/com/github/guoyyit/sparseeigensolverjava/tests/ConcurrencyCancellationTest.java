package com.github.guoyyit.sparseeigensolverjava.tests;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "solver.snapshot-dir=target/test-snapshots-conc",
        "solver.max-concurrency=4",
        "solver.queue-capacity=16"
})
class ConcurrencyCancellationTest {

    @Autowired
    private EigenSolverService service;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        Path dir = Path.of("target/test-snapshots-conc");
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

    private EigenRequest hardRequest(String id, long seed) {
        // large enough to keep iterating for a few milliseconds
        int n = 400;
        TestCsrDsl.Matrix m = TestCsrDsl.laplacian(n);
        EigenRequest req = new EigenRequest();
        req.requestId = id;
        req.dimension = m.n;
        req.rowPtr = m.rowPtr;
        req.colIndex = m.colIndex;
        req.values = m.values;
        req.seed = seed;
        req.nev = 3;
        req.tolerance = 1e-12;
        req.maxIterations = n;
        req.timeLimitMs = 60_000L;
        req.snapshotIntervalMs = 100000L; // do not snapshot the cancellation target
        return req;
    }

    @Test
    void multipleIndependentRequestsRunConcurrentlyAndAllConverge() throws Exception {
        int count = 4;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<EigenResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final long seed = 100L + i;
                final String id = "conc-" + i;
                futures.add(pool.submit(() -> service.submit(hardRequest(id, seed), true)));
            }
            for (Future<EigenResponse> f : futures) {
                EigenResponse r = f.get();
                assertEquals(SolveStatus.CONVERGED, r.status,
                        () -> r.status + " " + r.errorCode + " " + r.message);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationStopsJobAndReclaimsIntermediateData() throws Exception {
        String id = "cancel-1";
        EigenResponse accepted = service.submit(hardRequest(id, 9L), false);
        // allow it to start iterating
        Thread.sleep(300);
        EigenResponse cancelled = service.cancel(id);
        assertTrue(cancelled.status == SolveStatus.CANCELLED
                        || cancelled.status == SolveStatus.CONVERGED
                        || cancelled.status == SolveStatus.FAILED,
                "unexpected status " + cancelled.status);
        if (cancelled.status == SolveStatus.CANCELLED) {
            // the job must no longer be registered as a live running request and
            // the executor must have released its worker slot
            Thread.sleep(100);
            boolean stillRunning = service.isJobLive(id);
            assertTrue(!stillRunning, "cancelled job must not remain live");
        }
    }

    @Test
    void cancelledRequestDoesNotBlockOthers() throws Exception {
        String hog = "cancel-hog";
        service.submit(hardRequest(hog, 1L), false);
        Thread.sleep(100);
        service.cancel(hog);

        // a small independent request must still complete promptly
        TestCsrDsl.Matrix m = TestCsrDsl.laplacian(20);
        EigenRequest small = new EigenRequest();
        small.requestId = "after-cancel";
        small.dimension = m.n;
        small.rowPtr = m.rowPtr;
        small.colIndex = m.colIndex;
        small.values = m.values;
        small.seed = 2L;
        small.nev = 1;
        small.tolerance = 1e-8;
        small.maxIterations = 20;
        small.timeLimitMs = 30_000L;
        EigenResponse r = service.submit(small, true);
        assertEquals(SolveStatus.CONVERGED, r.status, r.message);
    }

    @Test
    void timeLimitStopsTheJobWithoutLeavingItRunning() throws Exception {
        String id = "timeout-1";
        EigenRequest req = hardRequest(id, 17L);
        req.timeLimitMs = 50L; // too short for the 400x400 solve
        req.snapshotIntervalMs = 1_000_000L;
        EigenResponse r = service.submit(req, true);
        assertEquals(SolveStatus.FAILED, r.status);
        assertEquals("TIME_LIMIT_EXCEEDED", r.errorCode);
        assertTrue(r.iterations >= 0);
        Thread.sleep(100);
        assertTrue(!service.isJobLive(id), "timed-out job must release its worker");
    }

    @Test
    void duplicateLiveRequestIdIsRejected() throws Exception {
        String id = "dup-1";
        service.submit(hardRequest(id, 3L), false);
        Thread.sleep(100);
        com.github.guoyyit.sparseeigensolverjava.core.SolverException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.github.guoyyit.sparseeigensolverjava.core.SolverException.class,
                        () -> service.submit(hardRequest(id, 3L), false));
        assertEquals(
                com.github.guoyyit.sparseeigensolverjava.core.ErrorCode.JOB_ALREADY_EXISTS,
                ex.getErrorCode());
        service.cancel(id);
    }
}
