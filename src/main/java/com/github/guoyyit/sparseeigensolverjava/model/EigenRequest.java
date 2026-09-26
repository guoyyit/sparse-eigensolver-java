package com.github.guoyyit.sparseeigensolverjava.model;

/** Solve request DTO. Raw CSR arrays describe the symmetric matrix. */
public class EigenRequest {

    public int dimension;
    public int[] rowPtr;
    public int[] colIndex;
    public double[] values;

    public String requestId;
    public Integer nev;
    public Double tolerance;
    public Integer maxIterations;
    public Double intervalLower;
    public Double intervalUpper;
    public Long seed;
    public Long timeLimitMs;
    public Long snapshotIntervalMs;

    public EigenRequest() {
    }
}
