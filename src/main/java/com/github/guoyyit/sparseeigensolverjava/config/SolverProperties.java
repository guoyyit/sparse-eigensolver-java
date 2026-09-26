package com.github.guoyyit.sparseeigensolverjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externally tunable service limits (application.properties, prefix "solver").
 */
@Component
@ConfigurationProperties(prefix = "solver")
public class SolverProperties {
    private long maxDimension = 200_000;
    private long maxNnz = 5_000_000;
    private int maxConcurrentTasks = 4;
    private long maxWallMillis = 60_000;
    private long maxMemoryBytes = 512L * 1024 * 1024;
    private String snapshotDir = "data/snapshots";
    private String auditFile = "data/audit.log";

    public long getMaxDimension() { return maxDimension; }
    public void setMaxDimension(long v) { this.maxDimension = v; }
    public long getMaxNnz() { return maxNnz; }
    public void setMaxNnz(long v) { this.maxNnz = v; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int v) { this.maxConcurrentTasks = v; }
    public long getMaxWallMillis() { return maxWallMillis; }
    public void setMaxWallMillis(long v) { this.maxWallMillis = v; }
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public void setMaxMemoryBytes(long v) { this.maxMemoryBytes = v; }
    public String getSnapshotDir() { return snapshotDir; }
    public void setSnapshotDir(String v) { this.snapshotDir = v; }
    public String getAuditFile() { return auditFile; }
    public void setAuditFile(String v) { this.auditFile = v; }
}
