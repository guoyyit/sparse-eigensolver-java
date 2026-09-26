package com.github.guoyyit.sparseeigensolverjava.core;

/** Business exception carrying a stable {@link ErrorCode}. */
public class SolverException extends RuntimeException {

    private final ErrorCode errorCode;

    public SolverException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SolverException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
