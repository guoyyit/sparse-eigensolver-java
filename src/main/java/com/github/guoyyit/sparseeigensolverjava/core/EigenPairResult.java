package com.github.guoyyit.sparseeigensolverjava.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * One approximate eigenpair with its convergence evidence.
 *
 * <p>Sign and normalization rule (fixed, see README): the eigenvector has
 * unit L2 norm and its largest-magnitude component is positive.</p>
 *
 * @param eigenvalue  approximate eigenvalue (Ritz value)
 * @param eigenvector approximate eigenvector, L2-normalized, sign-fixed
 * @param residualNorm ||A*v - lambda*v||_2 residual estimate
 * @param converged   whether residualNorm meets the requested tolerance
 * @param iterations  outer iterations spent when this pair was accepted
 */
public record EigenPairResult(double eigenvalue,
                              double[] eigenvector,
                              double residualNorm,
                              boolean converged,
                              int iterations) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
