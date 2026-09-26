package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.algo.LanczosState;

/**
 * Persistence of periodic Lanczos subspace snapshots.
 *
 * <p>A snapshot captures the complete Krylov basis and tridiagonal
 * coefficients, so after a process restart the solver continues from the most
 * recent snapshot instead of repeating earlier iterations.
 */
public interface SnapshotStore {

    /** Persists the state atomically (temporary file + rename). */
    void saveState(String requestId, LanczosState state);

    /** Loads the latest snapshot, or {@code null} when none exists. */
    LanczosState loadState(String requestId);

    /** Removes any snapshot and journal of the request. */
    void clearState(String requestId);

    /** Returns whether a snapshot currently exists for the request. */
    boolean exists(String requestId);
}
