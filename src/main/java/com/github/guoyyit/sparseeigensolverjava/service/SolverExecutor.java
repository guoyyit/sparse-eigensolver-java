package com.github.guoyyit.sparseeigensolverjava.service;

import java.util.concurrent.Future;

/** Bounded executor that runs solve jobs with per-request time/memory limits. */
public interface SolverExecutor {

    Future<?> submit(String requestId, Runnable task);

    boolean cancel(String requestId);

    boolean isRunning(String requestId);
}
