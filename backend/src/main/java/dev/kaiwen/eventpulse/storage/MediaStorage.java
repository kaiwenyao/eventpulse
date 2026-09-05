package dev.kaiwen.eventpulse.storage;

import java.util.Optional;

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

    /**
     * 浏览器可直连的绝对地址，图片字节因此不经过 api 进程。纯字符串拼接，
     * 不发起任何存储请求。
     *
     * 返回 empty 表示这个存储没有匿名可读的公开地址（本地磁盘，或 S3 未配置
     * public-base-url），调用方回落到 /api/media/images/{id} 代理路径。
     */
    Optional<String> publicUrl(String key);
}
