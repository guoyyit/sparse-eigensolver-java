package com.github.guoyyit.sparseeigensolverjava.api.dto;

import java.util.List;

/**
 * Solve response; failures carry code/message and empty eigenpairs.
 */
public class SolveResponseDto {
    public String taskId;
    public String status;
    public String errorCode;
    public String message;
    public List<EigenPairDto> eigenpairs;
    public int totalIterations;
    public long wallMillis;

    public static class EigenPairDto {
        public double eigenvalue;
        public double[] eigenvector;
        public double residualNorm;
        public boolean converged;
        public int iterations;
    }
}
