package com.ganzithon.backend.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {

    private static final Logger log =
            LoggerFactory.getLogger(ApiErrors.class);

    // TODO: API 명세서 확정 후 수정
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> known(ApiException e) {
        return ResponseEntity.status(e.status)
                .body(Map.of(
                        "code", e.code,
                        "message", e.getMessage()
                ));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<?> invalid(Exception e) {
        return known(ApiException.bad(
                "INVALID_REQUEST",
                "요청 형식을 확인해주세요."
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(Exception e) {
        return known(new ApiException(
                409,
                "CONFLICT",
                "이미 처리되었거나 중복된 요청이에요."
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> media(Exception e) {
        return known(new ApiException(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "JSON 요청 형식을 사용해주세요."
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(Exception e) {
        return known(new ApiException(
                405,
                "METHOD_NOT_ALLOWED",
                "지원하지 않는 요청 방식이에요."
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(Exception e) {
        return known(ApiException.missing());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("Unexpected API error", e);

        return known(new ApiException(
                500,
                "INTERNAL_ERROR",
                "처리 중 오류가 발생했어요. 잠시 후 다시 시도해주세요."
        ));
    }
}
