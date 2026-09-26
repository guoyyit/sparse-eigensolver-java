package com.github.guoyyit.sparseeigensolverjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit log for request lifecycle events, rejection reasons and resource
 * metering. Every record is emitted as a single structured log line so it can
 * be collected by log pipelines. A small request cannot be starved by large
 * requests: rejections happen up front and are audited before any heavy work.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger("solver.audit");

    public void record(String requestId, String event, String reason, long dimension,
                       long nnz, long bytesEstimate, long elapsedMs) {
        log.info("event={} requestId={} reason=\"{}\" n={} nnz={} estBytes={} elapsedMs={}",
                event, requestId, reason == null ? "" : reason.replace('"', '\''),
                dimension, nnz, bytesEstimate, elapsedMs);
    }

    public void accepted(String requestId, long dimension, long nnz, long bytesEstimate) {
        record(requestId, "ACCEPTED", null, dimension, nnz, bytesEstimate, 0L);
    }

    public void rejected(String requestId, String code, String message, long dimension,
                         long nnz, long bytesEstimate) {
        record(requestId, "REJECTED", code + ":" + message, dimension, nnz,
                bytesEstimate, 0L);
    }

    public void finished(String requestId, String status, long dimension, long nnz,
                         long elapsedMs) {
        record(requestId, "FINISHED", status, dimension, nnz, 0L, elapsedMs);
    }
}
