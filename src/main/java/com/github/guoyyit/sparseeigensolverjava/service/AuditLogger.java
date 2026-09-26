package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.config.SolverProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Append-only audit record of admissions, rejections and resource metering.
 * Every line is also emitted to the application log. The file gives operators
 * a durable trail explaining why a request was rejected and what resources
 * each accepted task consumed.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final Path auditFile;

    public AuditLogger(SolverProperties props) {
        this.auditFile = Path.of(props.getAuditFile());
        try {
            if (auditFile.getParent() != null) {
                Files.createDirectories(auditFile.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create audit dir for " + auditFile, e);
        }
    }

    /** A request was refused; code and reason are recorded. */
    public void rejected(String taskId, String code, String reason) {
        append("REJECTED task=%s code=%s reason=%s", taskId, code, reason);
    }

    /** A request passed validation and was admitted; records its size. */
    public void admitted(String taskId, int n, long nnz) {
        append("ADMITTED task=%s n=%d nnz=%d", taskId, n, nnz);
    }

    /** A task reached a terminal state; records wall time and memory estimate. */
    public void finished(String taskId, String status, long wallMillis, long peakBytes) {
        append("FINISHED task=%s status=%s wallMillis=%d peakBytes=%d",
                taskId, status, wallMillis, peakBytes);
    }

    /** A running task was cancelled by the caller. */
    public void cancelled(String taskId) {
        append("CANCELLED task=%s", taskId);
    }

    /** An interrupted task was resumed from its snapshot after a restart. */
    public void recovered(String taskId) {
        append("RECOVERED task=%s", taskId);
    }

    private synchronized void append(String fmt, Object... args) {
        String line = Instant.now() + " " + String.format(fmt, args);
        log.info("audit: {}", line);
        try {
            Files.writeString(auditFile, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Auditing must never break the compute path; log and continue.
            log.warn("audit write failed: {}", e.getMessage());
        }
    }
}
