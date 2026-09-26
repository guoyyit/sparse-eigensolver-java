package com.github.guoyyit.sparseeigensolverjava.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * Immutable solver configuration for one request.
 *
 * @param numEigenvalues number of smallest eigenpairs requested (>= 1)
 * @param tolerance      residual convergence tolerance, relative to ||A|| estimate (> 0)
 * @param maxIterations  maximum number of outer restart iterations (> 0)
 * @param intervalMin    optional inclusive lower bound; eigenvalues below are filtered out (nullable)
 * @param intervalMax    optional inclusive upper bound; eigenvalues above are filtered out (nullable)
 * @param seed           random seed for the deterministic starting vector
 * @param snapshotEvery  take a subspace snapshot every N outer iterations (0 disables)
 */
public record SolverOptions(int numEigenvalues,
                            double tolerance,
                            int maxIterations,
                            Double intervalMin,
                            Double intervalMax,
                            long seed,
                            int snapshotEvery) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static SolverOptions defaults(int numEigenvalues) {
        return new SolverOptions(numEigenvalues, 1e-8, 300, null, null, 42L, 25);
    }
}
