package com.eventpulse.common.error;

import java.time.Instant;
import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fieldErrors,
                       String traceId, Instant timestamp) {

    public static ApiError of(ErrorCode errorCode, String message, Map<String, String> fieldErrors, String traceId) {
        return new ApiError(errorCode.code(), message, fieldErrors, traceId, Instant.now());
    }
}
