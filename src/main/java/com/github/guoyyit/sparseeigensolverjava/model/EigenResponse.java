package com.github.guoyyit.sparseeigensolverjava.model;

import java.util.List;

/** Response DTO returned for submit / status / result calls. */
public class EigenResponse {

    public String requestId;
    public SolveStatus status;
    public List<EigenPairResult> results;
    public String errorCode;
    public String message;

    public int iterations;
    public int requestedNev;
    public Double intervalLower;
    public Double intervalUpper;
    public long seed;
    public long elapsedMs;

    public String signConvention;
    public String normalization;
    public String algorithm;
    public long snapshotIteration;

    public EigenResponse() {
    }
}
