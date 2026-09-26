package com.github.guoyyit.sparseeigensolverjava.api.dto;

/**
 * Synchronous solve request body.
 */
public class SolveRequestDto {
    public CsrMatrixDto matrix;
    public Integer numEigenvalues;
    public Double tolerance;
    public Integer maxIterations;
    public Double intervalMin;
    public Double intervalMax;
    public Long seed;
    public Integer snapshotEvery;
}
