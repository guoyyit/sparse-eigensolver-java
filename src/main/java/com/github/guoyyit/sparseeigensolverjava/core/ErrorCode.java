package com.github.guoyyit.sparseeigensolverjava.core;

/**
 * Machine-distinguishable error codes for rejected inputs and solver failures.
 */
public enum ErrorCode {
    /** Matrix dimension does not match the CSR index arrays. */
    DIMENSION_MISMATCH,
    /** Duplicate (row, col) index inside the CSR structure. */
    DUPLICATE_INDEX,
    /** NaN or infinite value in matrix or parameters. */
    NON_FINITE_VALUE,
    /** Structure is not a symmetric upper triangle (or values not symmetric). */
    ASYMMETRIC_STRUCTURE,
    /** Column index out of range. */
    INDEX_OUT_OF_RANGE,
    /** Invalid solver parameter (count, tolerance, iterations, interval). */
    INVALID_PARAMETER,
    /** Input exceeds configured size limits. */
    INPUT_TOO_LARGE,
    /** Service is at capacity and cannot accept more work. */
    OVERLOADED,
    /** Iteration budget exhausted before convergence. */
    NOT_CONVERGED,
    /** Numerical degeneracy detected (breakdown, invariant subspace, overflow). */
    NUMERICAL_DEGENERACY,
    /** Task was cancelled by the caller. */
    CANCELLED,
    /** Task exceeded its time budget. */
    TIMEOUT,
    /** Snapshot data is missing or corrupt. */
    SNAPSHOT_CORRUPT
}
