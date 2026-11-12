package com.craftpilot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiError> domain(ApiFailure failure) {
        return ResponseEntity.status(failure.status())
                .body(ApiError.of(failure.code(), failure.getMessage(), false));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ResponseEntity<ApiError> invalid() {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("INVALID_REQUEST", "Request validation failed", false));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException failure) {
        LOG.error("database_request_failed type={}", failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("DATABASE_UNAVAILABLE", "Database request could not be completed", true));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception failure) {
        if (failure instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(response.getStatusCode())
                    .body(ApiError.of("HTTP_" + response.getStatusCode().value(), "Request rejected", false));
        }
        LOG.error("request_failed type={}", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "Request could not be completed", false));
    }
}
