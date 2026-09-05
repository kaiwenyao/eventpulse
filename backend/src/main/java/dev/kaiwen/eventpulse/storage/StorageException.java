package dev.kaiwen.eventpulse.storage;

/**
 * 对象存储访问失败。区分两类语义：
 * OBJECT_NOT_FOUND —— 对象不存在（读取缺失图片，映射 404）；
 * UNAVAILABLE —— 存储不可达 / 凭证错误 / 其他服务端错误（映射 503）。
 * 消息只含 key 与操作名，绝不含凭证。
 */
public class StorageException extends RuntimeException {

    public enum Kind {
        OBJECT_NOT_FOUND,
        UNAVAILABLE
    }

    private final Kind kind;

    public StorageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static StorageException objectNotFound(String key, Throwable cause) {
        return new StorageException(Kind.OBJECT_NOT_FOUND, "Media object not found: " + key, cause);
    }

    public static StorageException unavailable(String operation, Throwable cause) {
        return new StorageException(Kind.UNAVAILABLE, "Media storage unavailable during " + operation, cause);
    }

    public Kind getKind() {
        return kind;
    }
}
