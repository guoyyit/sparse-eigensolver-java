package com.github.guoyyit.sparseeigensolverjava.api;

import com.github.guoyyit.sparseeigensolverjava.api.dto.ErrorResponseDto;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidationException;
import com.github.guoyyit.sparseeigensolverjava.core.RequestRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps validation, admission and capacity failures to distinguishable HTTP
 * responses: 400 for malformed input, 413 for size limits, 429 for capacity.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MatrixValidationException.class)
    public ResponseEntity<ErrorResponseDto> onValidation(MatrixValidationException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponseDto(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(RequestRejectedException.class)
    public ResponseEntity<ErrorResponseDto> onRejected(RequestRejectedException e) {
        HttpStatus status = e.code() == ErrorCode.OVERLOADED
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.PAYLOAD_TOO_LARGE;
        return ResponseEntity.status(status)
                .body(new ErrorResponseDto(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> onUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponseDto(ErrorCode.DIMENSION_MISMATCH.name(),
                        "malformed request body: " + e.getMessage()));
    }
}
