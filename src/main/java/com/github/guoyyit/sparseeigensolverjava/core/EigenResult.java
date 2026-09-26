package com.github.guoyyit.sparseeigensolverjava.core;

import java.util.List;

/**
 * Final outcome of a solve. When {@code status} is not CONVERGED the
 * eigenpairs list is empty: approximate results are never wrapped as success.
 *
 * @param status        terminal status
 * @param errorCode     machine-readable failure code, null on success
 * @param message       human-readable diagnostic
 * @param eigenpairs    converged eigenpairs (only when status == CONVERGED)
 * @param history       per-iteration diagnostics, kept for failures too
 * @param totalIterations outer iterations executed
 * @param wallMillis    wall-clock time spent in the solver
 */
public record EigenResult(SolveStatus status,
                          ErrorCode errorCode,
                          String message,
                          List<EigenPairResult> eigenpairs,
                          List<IterationRecord> history,
                          int totalIterations,
                          long wallMillis) {

    public static EigenResult success(List<EigenPairResult> pairs,
                                      List<IterationRecord> history,
                                      int totalIterations,
                                      long wallMillis) {
        return new EigenResult(SolveStatus.CONVERGED, null, "converged",
                List.copyOf(pairs), List.copyOf(history), totalIterations, wallMillis);
    }

    public static EigenResult failure(SolveStatus status,
                                      ErrorCode code,
                                      String message,
                                      List<IterationRecord> history,
                                      int totalIterations,
                                      long wallMillis) {
        return new EigenResult(status, code, message, List.of(),
                List.copyOf(history), totalIterations, wallMillis);
    }
}
