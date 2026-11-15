package com.ganzi.emori.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> known(ApiException e) {
        if (e.retryAt() != null) return ResponseEntity.status(e.status()).body(
                Map.of("error", Map.of("code", e.code(), "message", e.getMessage(), "retryAt", e.retryAt())));
        return error(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof DatabindException) {
                return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "입력값을 확인해주세요.");
            }
            cause = cause.getCause();
        }
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "JSON 요청 형식을 확인해주세요.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<?> invalid(Exception e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "입력값을 확인해주세요.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> missing(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 대상을 찾을 수 없습니다.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "요청이 현재 데이터와 충돌합니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("Unexpected API error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.");
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
