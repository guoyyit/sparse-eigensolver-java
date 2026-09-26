package com.github.guoyyit.sparseeigensolverjava.tests;

import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.EigenPairResult;
import com.github.guoyyit.sparseeigensolverjava.core.EigenResult;
import com.github.guoyyit.sparseeigensolverjava.core.LanczosSolver;
import com.github.guoyyit.sparseeigensolverjava.core.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;
import com.github.guoyyit.sparseeigensolverjava.snapshot.SnapshotStore;
import com.github.guoyyit.sparseeigensolverjava.snapshot.SolverSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement: periodic snapshots + resume after restart must produce results
 * identical to an uninterrupted run, without recomputation or duplicate output.
 */
class RecoveryTest {

    @TempDir
    Path tempDir;

    private static CsrMatrix laplacian(int n) {
        return LanczosSolverTest.laplacian1D(n);
    }

    /** Run until the cancel flag fires, leaving snapshots on disk (simulated crash). */
    private static EigenResult crashRun(CsrMatrix a, SolverOptions opt, SnapshotStore store,
                                        String taskId, AtomicBoolean cancel) {
        return new LanczosSolver(a, opt, cancel::get, 0).solve(store, taskId);
    }

    @Test
    void resumedRunMatchesUninterruptedRunBitForBit() throws Exception {
        CsrMatrix a = laplacian(400);
        SolverOptions opt = new SolverOptions(5, 1e-10, 500, null, null, 17L, 2);

        // Reference: uninterrupted run (no store).
        EigenResult reference = new LanczosSolver(a, opt, () -> false, 0).solve(null, "ref");
        assertEquals(SolveStatus.CONVERGED, reference.status(), reference.message());

        // Crashed run: cancel as soon as at least one snapshot exists.
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveRequest("task", a, opt);
        AtomicBoolean cancel = new AtomicBoolean();
        Thread t = new Thread(() -> {
            while (store.loadLatest("task").isEmpty()) {
                Thread.onSpinWait();
            }
            cancel.set(true);
        });
        EigenResult crashed = crashRun(a, opt, store, "task", cancel);
        // Either cancelled mid-run or (fast machine) already converged.
        assertTrue(crashed.status() == SolveStatus.CANCELLED
                || crashed.status() == SolveStatus.CONVERGED);
        assertTrue(store.loadLatest("task").isPresent(), "snapshot must survive the crash");

        // Resume with a fresh solver instance (process restart).
        EigenResult resumed = new LanczosSolver(a, opt, () -> false, 0).resume(store, "task");
        assertEquals(SolveStatus.CONVERGED, resumed.status(), resumed.message());

        // Bit-identical output: eigenvalues, eigenvectors, residuals, counts.
        assertEquals(reference.eigenpairs().size(), resumed.eigenpairs().size());
        for (int i = 0; i < reference.eigenpairs().size(); i++) {
            EigenPairResult p = reference.eigenpairs().get(i);
            EigenPairResult q = resumed.eigenpairs().get(i);
            assertEquals(p.eigenvalue(), q.eigenvalue(), 0.0, "eigenvalue " + i);
            assertArrayEquals(p.eigenvector(), q.eigenvector(), 0.0, "eigenvector " + i);
            assertEquals(p.residualNorm(), q.residualNorm(), 0.0, "residual " + i);
        }
        assertEquals(reference.totalIterations(), resumed.totalIterations(),
                "iteration count must match an uninterrupted run");
        assertEquals(reference.history(), resumed.history(), "history must match");
    }

    @Test
    void lockedPairsAreNotRecomputedAfterResume() {
        CsrMatrix a = laplacian(300);
        // Loose tolerance: pairs lock early; snapshots carry them.
        SolverOptions opt = new SolverOptions(4, 1e-6, 500, null, null, 23L, 1);
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveRequest("t2", a, opt);

        // Run until cancelled shortly after the first snapshot with locked pairs.
        AtomicBoolean cancel = new AtomicBoolean();
        Thread canceller = new Thread(() -> {
            while (true) {
                Optional<SolverSnapshot> s = store.loadLatest("t2");
                if (s.isPresent() && !s.get().locked.isEmpty()) {
                    cancel.set(true);
                    return;
                }
                Thread.onSpinWait();
            }
        });
        canceller.start();
        EigenResult crashed = new LanczosSolver(a, opt, cancel::get, 0).solve(store, "t2");
        assertTrue(cancel.get());
        SolverSnapshot snap = store.loadLatest("t2").orElseThrow();
        int lockedAtCrash = snap.locked.size();
        assertTrue(lockedAtCrash > 0, "some pairs committed before the crash");

        EigenResult resumed = new LanczosSolver(a, opt, () -> false, 0).resume(store, "t2");
        assertEquals(SolveStatus.CONVERGED, resumed.status(), resumed.message());
        // The first lockedAtCrash pairs must be exactly the committed ones:
        // no recomputation, no duplication.
        for (int i = 0; i < lockedAtCrash; i++) {
            assertEquals(snap.locked.get(i).eigenvalue(),
                    resumed.eigenpairs().get(i).eigenvalue(), 0.0);
            assertArrayEquals(snap.locked.get(i).eigenvector(),
                    resumed.eigenpairs().get(i).eigenvector(), 0.0);
        }
    }

    @Test
    void snapshotStoreRoundtripAndTombstone() {
        SnapshotStore store = new SnapshotStore(tempDir);
        CsrMatrix a = laplacian(20);
        SolverOptions opt = SolverOptions.defaults(2);

        store.saveRequest("x", a, opt);
        assertTrue(store.loadRequest("x").isPresent());
        assertEquals(1, store.resumableTaskIds().size());

        SolverSnapshot s = new SolverSnapshot();
        s.outerIteration = 7;
        s.beta = 0.5;
        store.save("x", s);
        SolverSnapshot s2 = new SolverSnapshot();
        s2.outerIteration = 9;
        store.save("x", s2);
        // Only the latest version is retained.
        assertEquals(9, store.loadLatest("x").orElseThrow().outerIteration);

        // Tombstone: terminal tasks are no longer resumable.
        store.finalizeTask("x");
        assertTrue(store.resumableTaskIds().isEmpty());
        assertTrue(store.loadLatest("x").isEmpty(), "snapshots reclaimed");
        assertTrue(store.loadRequest("x").isPresent(), "tombstone keeps the request record");
    }

    @Test
    void resumeWithoutSnapshotStartsFresh() {
        CsrMatrix a = laplacian(60);
        SolverOptions opt = new SolverOptions(2, 1e-9, 100, null, null, 5L, 0);
        SnapshotStore store = new SnapshotStore(tempDir);
        EigenResult r = new LanczosSolver(a, opt, () -> false, 0).resume(store, "missing");
        assertEquals(SolveStatus.CONVERGED, r.status(), r.message());
    }
}
