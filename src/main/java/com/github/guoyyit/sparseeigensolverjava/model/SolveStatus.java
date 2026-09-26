package com.github.guoyyit.sparseeigensolverjava.model;

/** Lifecycle status of an eigensolve job. */
public enum SolveStatus {
    PENDING,
    RUNNING,
    CONVERGED,
    FAILED,
    CANCELLED
}
