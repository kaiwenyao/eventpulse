package dev.kaiwen.eventpulse.exception;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> fieldErrors;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, String> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "resource not found");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
