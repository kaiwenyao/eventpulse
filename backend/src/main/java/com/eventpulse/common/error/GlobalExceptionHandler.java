package com.eventpulse.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eventpulse.booking.IdempotencyService;
import com.eventpulse.common.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Completed-request replay: return the stored response and status verbatim. */
    @ExceptionHandler(IdempotencyService.IdempotentReplay.class)
    public ResponseEntity<Object> handleReplay(IdempotencyService.IdempotentReplay ex) {
        Object body = ex.response == null ? Map.of() : ex.response;
        return ResponseEntity.status(ex.statusCode).body(body);
    }

    /** First transaction still in flight: ask the client to retry. */
    @ExceptionHandler(IdempotencyService.IdempotencyInProgress.class)
    public ResponseEntity<Object> handleInProgress(IdempotencyService.IdempotencyInProgress ex) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Retry-After", "1")
                .body(ApiError.of(ErrorCode.CONFLICT, ex.getMessage(), Map.of(), TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(org.springframework.security.authorization.AuthorizationDeniedException ex) {
        return ResponseEntity.status(403)
                .body(ApiError.of(ErrorCode.FORBIDDEN, "forbidden", Map.of(), TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.errorCode().status())
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), ex.fieldErrors(), TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED, "request validation failed", fields,
                        TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.MALFORMED_REQUEST, "malformed request body", Map.of(),
                        TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.MALFORMED_REQUEST, "invalid parameter: " + ex.getName(), Map.of(),
                        TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404)
                .body(ApiError.of(ErrorCode.NOT_FOUND, "resource not found", Map.of(),
                        TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(ErrorCode.INTERNAL, "internal error", Map.of(),
                        TraceIdFilter.currentTraceId()));
    }
}
