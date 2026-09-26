package com.github.guoyyit.sparseeigensolverjava.core;

/**
 * Thrown when a request is refused before compute starts: size limits,
 * resource budgets or service capacity. Carries a machine-readable code.
 */
public class RequestRejectedException extends RuntimeException {
    private final ErrorCode code;

    public RequestRejectedException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
