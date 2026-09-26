package com.github.guoyyit.sparseeigensolverjava.web;

import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps domain exceptions to HTTP responses. Every business error keeps its
 * stable {@link ErrorCode} name in the response body so callers can distinguish
 * every invalid-input / limit / failure category.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<ErrorCode, HttpStatus> STATUS = new EnumMap<>(ErrorCode.class);

    static {
        // malformed request content
        for (ErrorCode c : new ErrorCode[] {
                ErrorCode.INVALID_DIMENSION, ErrorCode.INVALID_POINTER,
                ErrorCode.DUPLICATE_INDEX, ErrorCode.INDEX_OUT_OF_RANGE,
                ErrorCode.NON_FINITE_VALUE, ErrorCode.NON_SYMMETRIC_UPPER_TRIANGLE,
                ErrorCode.INVALID_NEV, ErrorCode.INVALID_TOLERANCE,
                ErrorCode.INVALID_MAX_ITERATIONS, ErrorCode.INVALID_INTERVAL,
                ErrorCode.INVALID_CONFIG, ErrorCode.INVALID_SEED}) {
            STATUS.put(c, HttpStatus.BAD_REQUEST);
        }
        STATUS.put(ErrorCode.MATRIX_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE);
        STATUS.put(ErrorCode.RESOURCE_LIMIT, HttpStatus.TOO_MANY_REQUESTS);
        STATUS.put(ErrorCode.JOB_NOT_FOUND, HttpStatus.NOT_FOUND);
        STATUS.put(ErrorCode.JOB_ALREADY_EXISTS, HttpStatus.CONFLICT);
        STATUS.put(ErrorCode.SNAPSHOT_CORRUPT, HttpStatus.INTERNAL_SERVER_ERROR);
        STATUS.put(ErrorCode.SNAPSHOT_MISMATCH, HttpStatus.CONFLICT);
        STATUS.put(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
        // solver outcomes surfaced synchronously:
        STATUS.put(ErrorCode.NOT_CONVERGED, HttpStatus.UNPROCESSABLE_ENTITY);
        STATUS.put(ErrorCode.NUMERICAL_BREAKDOWN, HttpStatus.UNPROCESSABLE_ENTITY);
        STATUS.put(ErrorCode.TIME_LIMIT_EXCEEDED, HttpStatus.SERVICE_UNAVAILABLE);
        STATUS.put(ErrorCode.CANCELLED, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(SolverException.class)
    public ResponseEntity<ApiError> handleSolver(SolverException ex) {
        HttpStatus http = STATUS.getOrDefault(ex.getErrorCode(),
                HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(http)
                .body(new ApiError(null, ex.getErrorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(null, ErrorCode.INVALID_CONFIG.name(),
                        "malformed JSON request: " + ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(null, ErrorCode.INTERNAL_ERROR.name(),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }
}
