package com.craftpilot.common;

import org.slf4j.MDC;

public record ApiError(String code, String message, String traceId, boolean retryable) {
    public static ApiError of(String code, String message, boolean retryable) {
        return new ApiError(code, message, MDC.get("traceId"), retryable);
    }
}
