package dev.kaiwen.eventpulse.storage;

/**
 * 图片对象的底层存储抽象：upload/read/purge 都只面向这个接口。
 * 生产与多副本部署用 {@link S3MediaStorage}（SeaweedFS 等 S3 兼容服务），
 * 本地开发可回落 {@link LocalStorageMediaStorage}，业务层不感知差别。
 */
public interface MediaStorage {

    /**
     * 写入对象。key 由后端生成（UUID 前缀），contentType 必须随对象保存。
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * 读取对象。对象不存在抛 {@link StorageException}，kind 为
     * OBJECT_NOT_FOUND；服务不可用/凭证错误等抛 kind 为 UNAVAILABLE 的异常。
     */
    byte[] get(String key);

    /**
     * 删除对象。对象不存在视为成功（幂等），便于软删除后的清理任务重放。
     */
    void delete(String key);
}