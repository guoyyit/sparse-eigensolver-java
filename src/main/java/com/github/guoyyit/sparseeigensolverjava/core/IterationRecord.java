package com.github.guoyyit.sparseeigensolverjava.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * One outer-iteration diagnostic line, retained for post-mortem analysis.
 *
 * @param iteration        outer iteration index (0-based)
 * @param subspaceDim      current Krylov subspace dimension
 * @param bestResidualNorm smallest residual among unconverged wanted pairs
 * @param convergedCount   number of converged wanted pairs so far
 */
public record IterationRecord(int iteration,
                              int subspaceDim,
                              double bestResidualNorm,
                              int convergedCount) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
