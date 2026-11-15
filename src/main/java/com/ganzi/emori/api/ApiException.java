package com.ganzi.emori.api;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String retryAt;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAt = null;
    }

    public ApiException(HttpStatus status, String code, String message, String retryAt) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAt = retryAt;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String retryAt() { return retryAt; }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message);
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "유효한 로그인이 필요합니다.");
    }
}
