package com.github.guoyyit.sparseeigensolverjava.core;

/**
 * Thrown when input validation fails; carries a machine-readable error code.
 */
public class MatrixValidationException extends RuntimeException {
    private final ErrorCode code;

    public MatrixValidationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
