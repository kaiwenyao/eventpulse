package dev.kaiwen.eventpulse.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试用内存 MediaStorage：记录 put/delete 结果，可按操作注入「存储不可用」
 * 失败，让业务/清理逻辑不依赖任何真实 IO。
 */
public class InMemoryMediaStorage implements MediaStorage {

    public final Map<String, byte[]> objects = new HashMap<>();
    public final Map<String, String> contentTypes = new HashMap<>();

    public boolean failOnPut;
    public boolean failOnGet;
    public boolean failOnDelete;
    /** 只对这些 key 注入删除失败，其余照常成功（模拟部分对象删除失败）。 */
    public final java.util.Set<String> failDeleteKeys = new java.util.HashSet<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (failOnPut) {
            throw StorageException.unavailable("injected put failure", null);
        }
        objects.put(key, bytes);
        contentTypes.put(key, contentType);
    }

    @Override
    public byte[] get(String key) {
        if (failOnGet) {
            throw StorageException.unavailable("injected get failure", null);
        }
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw StorageException.objectNotFound(key, null);
        }
        return bytes;
    }

    @Override
    public void delete(String key) {
        if (failOnDelete || failDeleteKeys.contains(key)) {
            throw StorageException.unavailable("injected delete failure", null);
        }
        objects.remove(key);
        contentTypes.remove(key);
    }
}