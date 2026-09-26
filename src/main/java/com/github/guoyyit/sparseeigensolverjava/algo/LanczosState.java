package com.github.guoyyit.sparseeigensolverjava.algo;

import java.io.Serializable;

/**
 * Serializable Lanczos iteration state used for periodic snapshots and for
 * process-restart recovery.
 *
 * <p>All floating point fields are written/read exactly (Java binary
 * serialization), so resuming from a snapshot repeats exactly the same
 * arithmetic as an uninterrupted run.
 */
public class LanczosState implements Serializable {

    private static final long serialVersionUID = 1L;

    // ---- immutable problem/config fingerprint ----
    public String requestId;
    public int inputHash;
    public long seed;
    public int nev;
    public double tolerance;
    public Double intervalLower;
    public Double intervalUpper;
    public int n;
    public int maxIterations;

    // ---- Krylov subspace (the recoverable work) ----
    /** Number of completed Lanczos steps. */
    public int j;
    /** basis[0..j-1] are q1..qj; basis[j] is the look-ahead q_{j+1}. */
    public double[][] basis;
    /** alpha[k] = diagonal of T, length maxIterations, valid 0..j-1. */
    public double[] alphas;
    /** beta[k] = off-diagonal between q_k and q_{k+1}, index k in 1..j-1. */
    public double[] betas;

    // ---- lifecycle ----
    public boolean finished;
    public String status;
    public String errorCode;
    public String message;

    // ---- diagnostics retained on failure ----
    public double[] lastResidualEstimates;
    public double[] lastRitzValues;

    // ---- committed final results (journal; never emitted twice) ----
    public int committedCount;
    public double[] finalValues;
    public double[] finalResiduals;
    public boolean[] finalConverged;
    public double[][] finalVectors;

    public long createdAt;
    public long updatedAt;

    public LanczosState() {
    }
}
