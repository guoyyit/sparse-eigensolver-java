package com.github.guoyyit.sparseeigensolverjava.model;

/** One approximate eigenpair with convergence diagnostics. */
public class EigenPairResult {

    public double eigenvalue;
    public double[] eigenvector;
    public double residual;
    public boolean converged;

    public EigenPairResult() {
    }
}
