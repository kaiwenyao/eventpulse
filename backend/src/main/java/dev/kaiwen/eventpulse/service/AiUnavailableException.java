package dev.kaiwen.eventpulse.service;

/**
 * Python AI 服务不可用 / 返回无法理解的结果时抛出。
 * message 面向用户展示（不含密钥、提示词或模型原文），由 GlobalExceptionHandler
 * 统一转成 503 的 Result JSON。
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String safeMessage) {
        super(safeMessage);
    }
}
