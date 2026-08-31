package dev.kaiwen.eventpulse.exception;

/** 业务错误，会被 GlobalExceptionHandler 转成 Result.error。 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
