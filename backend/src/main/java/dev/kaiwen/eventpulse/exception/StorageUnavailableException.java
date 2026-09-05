package dev.kaiwen.eventpulse.exception;

/**
 * 对象存储不可用（S3 网络故障、凭证错误、服务端 5xx 等）。与业务校验错误
 * 区分开：这是预期的服务状态而不是请求问题，GlobalExceptionHandler 转成
 * 503，监控据此区分「存储暂时不可用」与真实的服务器错误。
 * message 面向用户且不含内部细节；cause 只进服务端日志。
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
