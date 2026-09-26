package com.github.guoyyit.sparseeigensolverjava.service;

/**
 * Per-request resource ceilings enforced by the task manager.
 *
 * @param maxDimension       maximum matrix dimension accepted
 * @param maxNnz             maximum number of stored nonzeros accepted
 * @param maxConcurrentTasks maximum simultaneously running tasks
 * @param maxWallMillis      per-task wall-clock budget
 * @param maxMemoryBytes     per-task estimated heap budget
 */
public record ResourceLimits(long maxDimension,
                             long maxNnz,
                             int maxConcurrentTasks,
                             long maxWallMillis,
                             long maxMemoryBytes) {

    public static ResourceLimits defaults() {
        return new ResourceLimits(200_000, 5_000_000, 4, 60_000, 512L * 1024 * 1024);
    }
}
