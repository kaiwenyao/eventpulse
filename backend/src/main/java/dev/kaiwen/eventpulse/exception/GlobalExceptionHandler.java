package dev.kaiwen.eventpulse.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.service.AiUnavailableException;

/**
 * 统一把异常转成 {@link Result} JSON。
 *
 * 响应显式声明 application/json：错误响应不能受请求 Accept 头的内容协商影响。
 * SSE 订阅带的是 {@code Accept: text/event-stream}，若在这里再去协商，Spring
 * 找不到能把 Result 写成 text/event-stream 的转换器，就会抛
 * HttpMediaTypeNotAcceptableException，把 403/404 这类业务错误统统变成空 body
 * 的 500 —— 前端只能当成临时故障无限重连。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        return json(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return json(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * AI 降级是预期的服务状态（上游不可用 / 未配置 Key），返回 503 而不是 500：
     * 监控据此区分「AI 暂时不可用」与真实的服务器错误，告警与重试语义才正确。
     * message 本身面向用户且不含内部细节。
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<Result<Void>> handleAiUnavailable(AiUnavailableException ex) {
        return json(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception ex) {
        return json(HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage() == null ? "Server error" : ex.getMessage());
    }

    private static ResponseEntity<Result<Void>> json(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(message));
    }
}
