package com.github.guoyyit.sparseeigensolverjava.web;

/** Standard error body with a stable machine-readable code. */
public class ApiError {

    public String requestId;
    public String code;
    public String message;
    public long timestamp;

    public ApiError() {
    }

    public ApiError(String requestId, String code, String message) {
        this.requestId = requestId;
        this.code = code;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
}
