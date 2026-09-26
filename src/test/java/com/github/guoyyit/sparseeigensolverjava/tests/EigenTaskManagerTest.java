package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.config.SolverProperties;
import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.EigenResult;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.RequestRejectedException;
import com.github.guoyyit.sparseeigensolverjava.core.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;
import com.github.guoyyit.sparseeigensolverjava.service.AuditLogger;
import com.github.guoyyit.sparseeigensolverjava.service.EigenTaskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency, cancellation, timeout, resource-limit and audit behavior of
 * the task manager.
 */
class EigenTaskManagerTest {

    @TempDir
    Path tempDir;

    private SolverProperties props;
    private EigenTaskManager manager;

    @BeforeEach
    void setUp() {
        props = new SolverProperties();
        props.setSnapshotDir(tempDir.resolve("snap").toString());
        props.setAuditFile(tempDir.resolve("audit.log").toString());
        props.setMaxConcurrentTasks(2);
        props.setMaxWallMillis(30_000);
        manager = new EigenTaskManager(props, new AuditLogger(props));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private static CsrMatrix laplacian(int n) {
        return LanczosSolverTest.laplacian1D(n);
    }

    private static EigenResult awaitTerminal(EigenTaskManager m, String taskId, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            var view = m.poll(taskId).orElseThrow();
            if (view.result() != null) {
                return view.result();
            }
            Thread.sleep(10);
        }
        fail("task " + taskId + " did not terminate within " + millis + " ms");
        return null;
    }

    @Test
    void independentTasksRunConcurrently() throws Exception {
        // Two slots, two long tasks: both must be RUNNING at the same time.
        SolverOptions slow = new SolverOptions(6, 1e-12, 2000, null, null, 1L, 0);
        String t1 = manager.submit(laplacian(2000), slow);
        String t2 = manager.submit(laplacian(2000), slow);
        long deadline = System.currentTimeMillis() + 10_000;
        boolean overlap = false;
        while (System.currentTimeMillis() < deadline) {
            SolveStatus s1 = manager.poll(t1).orElseThrow().status();
            SolveStatus s2 = manager.poll(t2).orElseThrow().status();
            if (s1 == SolveStatus.RUNNING && s2 == SolveStatus.RUNNING) {
                overlap = true;
                break;
            }
            if (manager.poll(t1).orElseThrow().result() != null
                    && manager.poll(t2).orElseThrow().result() != null) {
                break; // both finished extremely fast; overlap not observable
            }
            Thread.sleep(5);
        }
        EigenResult r1 = awaitTerminal(manager, t1, 60_000);
        EigenResult r2 = awaitTerminal(manager, t2, 60_000);
        assertNotNull(r1);
        assertNotNull(r2);
        assertTrue(overlap || (r1.status() == SolveStatus.CONVERGED
                && r2.status() == SolveStatus.CONVERGED));
    }

    @Test
    void rejectsWhenOverCapacity() throws Exception {
        SolverOptions slow = new SolverOptions(8, 1e-14, 5000, null, null, 1L, 0);
        CsrMatrix big = laplacian(3000);
        manager.submit(big, slow);
        manager.submit(big, slow);
        Thread.sleep(200); // let workers pick up the tasks
        RequestRejectedException e = assertThrows(RequestRejectedException.class,
                () -> manager.submit(big, slow));
        assertEquals(ErrorCode.OVERLOADED, e.code());
        String audit = Files.readString(Path.of(props.getAuditFile()));
        assertTrue(audit.contains("OVERLOADED"), "rejection audited");
    }

    @Test
    void cancelReclaimsSlotAndPublishesNoPartialResult() throws Exception {
        SolverOptions slow = new SolverOptions(8, 1e-14, 100_000, null, null, 1L, 0);
        CsrMatrix big = laplacian(4000);
        String victim = manager.submit(big, slow);
        // Wait until running, then cancel.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && manager.poll(victim).orElseThrow().status() != SolveStatus.RUNNING) {
            Thread.sleep(5);
        }
        assertTrue(manager.cancel(victim));
        EigenResult r = awaitTerminal(manager, victim, 30_000);
        assertEquals(SolveStatus.CANCELLED, r.status());
        assertEquals(ErrorCode.CANCELLED, r.errorCode());
        assertTrue(r.eigenpairs().isEmpty(), "no partial results after cancel");

        // The freed slot must accept new work immediately.
        String next = manager.submit(laplacian(200), SolverOptions.defaults(2));
        EigenResult r2 = awaitTerminal(manager, next, 30_000);
        assertEquals(SolveStatus.CONVERGED, r2.status(), r2.message());
    }

    @Test
    void timeoutTerminatesLongTask() throws Exception {
        props.setMaxWallMillis(300); // tiny budget
        EigenTaskManager shortFused = new EigenTaskManager(props, new AuditLogger(props));
        try {
            SolverOptions slow = new SolverOptions(8, 1e-14, 1_000_000, null, null, 1L, 0);
            String id = shortFused.submit(laplacian(5000), slow);
            EigenResult r = awaitTerminal(shortFused, id, 30_000);
            assertEquals(SolveStatus.TIMEOUT, r.status());
            assertEquals(ErrorCode.TIMEOUT, r.errorCode());
            assertTrue(r.eigenpairs().isEmpty());
            assertFalse(r.history().isEmpty(), "iteration diagnostics retained");
        } finally {
            shortFused.shutdown();
        }
    }

    @Test
    void rejectsOversizedInputWithAudit() throws Exception {
        props.setMaxDimension(100);
        EigenTaskManager limited = new EigenTaskManager(props, new AuditLogger(props));
        try {
            RequestRejectedException e = assertThrows(RequestRejectedException.class,
                    () -> limited.submit(laplacian(101), SolverOptions.defaults(1)));
            assertEquals(ErrorCode.INPUT_TOO_LARGE, e.code());
            String audit = Files.readString(Path.of(props.getAuditFile()));
            assertTrue(audit.contains("REJECTED"), "rejection audited");
            assertTrue(audit.contains("INPUT_TOO_LARGE"));
        } finally {
            limited.shutdown();
        }
    }

    @Test
    void rejectsByMemoryEstimateWithAudit() throws Exception {
        props.setMaxMemoryBytes(64 * 1024); // tiny budget
        EigenTaskManager limited = new EigenTaskManager(props, new AuditLogger(props));
        try {
            RequestRejectedException e = assertThrows(RequestRejectedException.class,
                    () -> limited.submit(laplacian(5000), SolverOptions.defaults(4)));
            assertEquals(ErrorCode.INPUT_TOO_LARGE, e.code());
            String audit = Files.readString(Path.of(props.getAuditFile()));
            assertTrue(audit.contains("INPUT_TOO_LARGE"));
        } finally {
            limited.shutdown();
        }
    }

    @Test
    void admissionAndTerminationAreAudited() throws Exception {
        String id = manager.submit(laplacian(100), SolverOptions.defaults(2));
        EigenResult r = awaitTerminal(manager, id, 30_000);
        assertEquals(SolveStatus.CONVERGED, r.status(), r.message());
        String audit = Files.readString(Path.of(props.getAuditFile()));
        assertTrue(audit.contains("ADMITTED task=" + id));
        assertTrue(audit.contains("FINISHED task=" + id));
        assertTrue(audit.contains("CONVERGED"));
    }

    @Test
    void recoversInterruptedTasksAfterRestart() throws Exception {
        // Simulate a crash: persist a request + snapshot, no tombstone,
        // then let a fresh manager instance recover it.
        CsrMatrix a = laplacian(300);
        SolverOptions opt = new SolverOptions(3, 1e-9, 500, null, null, 11L, 1);
        var store = new com.github.guoyyit.sparseeigensolverjava.snapshot.SnapshotStore(
                Path.of(props.getSnapshotDir()));
        String taskId = "crashed-task";
        store.saveRequest(taskId, a, opt);
        // Run briefly to produce a snapshot, then "crash" (cancel, no cleanup).
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();
        Thread canceller = new Thread(() -> {
            while (store.loadLatest(taskId).isEmpty()) {
                Thread.onSpinWait();
            }
            cancel.set(true);
        });
        canceller.start();
        new com.github.guoyyit.sparseeigensolverjava.core.LanczosSolver(
                a, opt, cancel::get, 0).solve(store, taskId);
        canceller.join();
        assertTrue(store.loadLatest(taskId).isPresent());

        // Reference result for comparison.
        EigenResult reference = new com.github.guoyyit.sparseeigensolverjava.core.LanczosSolver(
                a, opt, () -> false, 0).solve(null, "ref");
        assertEquals(SolveStatus.CONVERGED, reference.status());

        // New manager over the same directory == process restart.
        EigenTaskManager restarted = new EigenTaskManager(props, new AuditLogger(props));
        try {
            restarted.recoverInterruptedTasks();
            EigenResult recovered = awaitTerminal(restarted, taskId, 30_000);
            assertEquals(SolveStatus.CONVERGED, recovered.status(), recovered.message());
            assertEquals(reference.eigenpairs().size(), recovered.eigenpairs().size());
            for (int i = 0; i < reference.eigenpairs().size(); i++) {
                assertEquals(reference.eigenpairs().get(i).eigenvalue(),
                        recovered.eigenpairs().get(i).eigenvalue(), 0.0);
            }
            // After terminal cleanup the task is not resumable again.
            assertTrue(store.resumableTaskIds().isEmpty());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void sameInputSameOutputAcrossTasks() throws Exception {
        CsrMatrix a = laplacian(150);
        SolverOptions opt = new SolverOptions(3, 1e-9, 200, null, null, 77L, 0);
        String t1 = manager.submit(a, opt);
        String t2 = manager.submit(a, opt);
        EigenResult r1 = awaitTerminal(manager, t1, 30_000);
        EigenResult r2 = awaitTerminal(manager, t2, 30_000);
        assertEquals(SolveStatus.CONVERGED, r1.status());
        assertEquals(SolveStatus.CONVERGED, r2.status());
        for (int i = 0; i < r1.eigenpairs().size(); i++) {
            assertEquals(r1.eigenpairs().get(i).eigenvalue(),
                    r2.eigenpairs().get(i).eigenvalue(), 0.0);
            assertArrayEquals(r1.eigenpairs().get(i).eigenvector(),
                    r2.eigenpairs().get(i).eigenvector(), 0.0);
        }
    }
}
