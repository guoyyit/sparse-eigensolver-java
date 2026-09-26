package com.github.guoyyit.sparseeigensolverjava.api.dto;

/**
 * Uniform error envelope: machine-readable code plus explanation.
 */
public class ErrorResponseDto {
    public String errorCode;
    public String message;

    public ErrorResponseDto(String errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }
}
