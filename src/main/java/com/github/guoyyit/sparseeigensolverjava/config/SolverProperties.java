package com.github.guoyyit.sparseeigensolverjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable limits for the eigensolver service (prefix {@code solver}).
 *
 * <p>Overridable in {@code application.properties} or via test/runtime
 * properties, e.g. {@code solver.max-dimension=4000}. Standard JavaBean
 * accessors are required for Spring Boot relaxed binding.
 */
@ConfigurationProperties(prefix = "solver")
public class SolverProperties {

    private int maxDimension = 4000;
    private long maxNonZeros = 20_000_000L;
    private long maxRequestBytes = 512L * 1024 * 1024;
    private long perRequestMemoryBytes = 512L * 1024 * 1024;
    private int maxConcurrency = 4;
    private long defaultTimeLimitMs = 60_000L;
    private long maxTimeLimitMs = 600_000L;
    private int defaultMaxIterations = 300;
    private int maxIterations = 2000;
    private int queueCapacity = 256;
    private String snapshotDir = "data/snapshots";
    private long snapshotIntervalMs = 1000L;

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    public long getMaxNonZeros() {
        return maxNonZeros;
    }

    public void setMaxNonZeros(long maxNonZeros) {
        this.maxNonZeros = maxNonZeros;
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public long getPerRequestMemoryBytes() {
        return perRequestMemoryBytes;
    }

    public void setPerRequestMemoryBytes(long perRequestMemoryBytes) {
        this.perRequestMemoryBytes = perRequestMemoryBytes;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public long getDefaultTimeLimitMs() {
        return defaultTimeLimitMs;
    }

    public void setDefaultTimeLimitMs(long defaultTimeLimitMs) {
        this.defaultTimeLimitMs = defaultTimeLimitMs;
    }

    public long getMaxTimeLimitMs() {
        return maxTimeLimitMs;
    }

    public void setMaxTimeLimitMs(long maxTimeLimitMs) {
        this.maxTimeLimitMs = maxTimeLimitMs;
    }

    public int getDefaultMaxIterations() {
        return defaultMaxIterations;
    }

    public void setDefaultMaxIterations(int defaultMaxIterations) {
        this.defaultMaxIterations = defaultMaxIterations;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    public void setSnapshotDir(String snapshotDir) {
        this.snapshotDir = snapshotDir;
    }

    public long getSnapshotIntervalMs() {
        return snapshotIntervalMs;
    }

    public void setSnapshotIntervalMs(long snapshotIntervalMs) {
        this.snapshotIntervalMs = snapshotIntervalMs;
    }
}
