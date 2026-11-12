package com.craftpilot.common;

import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ApiFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    public static ApiFailure forbidden() {
        return new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions");
    }

    public static ApiFailure protectedOwner() {
        return new ApiFailure(HttpStatus.CONFLICT, "OWNER_MEMBERSHIP_PROTECTED", "Owner membership cannot be revoked");
    }

    public static ApiFailure idempotencyConflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Command key was used for a different request");
    }

    public static ApiFailure conflict(String code, String message) {
        return new ApiFailure(HttpStatus.CONFLICT, code, message);
    }

    public static ApiFailure invalid(String message) {
        return new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", message);
    }

    public static ApiFailure unavailable(String code, String message) {
        return new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
