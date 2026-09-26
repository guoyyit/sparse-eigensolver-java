package com.github.guoyyit.sparseeigensolverjava.core;

/**
 * Lifecycle status of a solve task.
 */
public enum SolveStatus {
    PENDING,
    RUNNING,
    /** All requested eigenpairs converged. */
    CONVERGED,
    /** Terminated without full convergence; no success-wrapped results. */
    FAILED,
    CANCELLED,
    TIMEOUT
}
