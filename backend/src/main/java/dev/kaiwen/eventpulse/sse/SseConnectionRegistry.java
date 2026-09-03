package dev.kaiwen.eventpulse.sse;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.exception.BusinessException;

/**
 * 本实例的 SSE 连接注册表。一个 bookingId 可以有多个连接（多个标签页），
 * 每个连接有独立的 connectionId，完成/超时/出错时只删除自己。
 *
 * 这里只保存临时网络资源：有哪些浏览器连在本实例上、如何向它们写数据。
 * 任何业务结果（订单状态、通知）都不保存在这里；API 重启后连接断开，
 * 浏览器自动重连并从 PostgreSQL 取最新状态。
 */
@Component
@Profile("api")
public class SseConnectionRegistry {

    /** bookingId 为空表示用户级频道（购物车 / 钱包 / 订单列表的刷新提醒）。 */
    private record Connection(Long bookingId, Long userId, SseEmitter emitter) {
    }

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byBooking = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byUser = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userChannels = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final int maxPerBooking;
    private final int maxPerUser;
    private final int maxUserChannels;

    /** 用户级刷新频道的连接数上限默认与 per-user 上限一致。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SseConnectionRegistry(
            @Value("${eventpulse.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${eventpulse.sse.max-connections-per-booking:5}") int maxPerBooking,
            @Value("${eventpulse.sse.max-connections-per-user:20}") int maxPerUser) {
        this(timeoutMs, maxPerBooking, maxPerUser, maxPerUser);
    }

    public SseConnectionRegistry(long timeoutMs, int maxPerBooking, int maxPerUser, int maxUserChannels) {
        this.timeoutMs = timeoutMs;
        this.maxPerBooking = maxPerBooking;
        this.maxPerUser = maxPerUser;
        this.maxUserChannels = maxUserChannels;
    }

    /**
     * 注册一条新连接。限制单个订单与单个用户的连接数，防止连接无限增长。
     */
    public SseEmitter register(Long bookingId, Long userId) {
        if (countByBooking(bookingId) >= maxPerBooking) {
            throw BusinessException.conflict("Too many live connections for this booking");
        }
        if (countByUser(userId) >= maxPerUser) {
            throw BusinessException.conflict("Too many live connections for this user");
        }
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = newEmitter();
        emitter.onCompletion(() -> remove(connectionId));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(connectionId);
        });
        emitter.onError(e -> remove(connectionId));
        connections.put(connectionId, new Connection(bookingId, userId, emitter));
        byBooking.computeIfAbsent(bookingId, key -> ConcurrentHashMap.newKeySet()).add(connectionId);
        byUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(connectionId);
        return emitter;
    }

    /**
     * 注册用户级刷新频道（已登录即可，只收自己账号的变化提醒）。
     * 与订单级订阅共用 per-user 连接上限，另设独立频道数上限。
     */
    public SseEmitter registerUserChannel(Long userId) {
        if (userChannels.getOrDefault(userId, Set.of()).size() >= maxUserChannels) {
            throw BusinessException.conflict("Too many live connections for this user");
        }
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = newEmitter();
        emitter.onCompletion(() -> remove(connectionId));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(connectionId);
        });
        emitter.onError(e -> remove(connectionId));
        connections.put(connectionId, new Connection(null, userId, emitter));
        userChannels.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(connectionId);
        byUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(connectionId);
        return emitter;
    }

    private SseEmitter newEmitter() {
        return new SseEmitter(timeoutMs);
    }

    /** 只删除自己的连接，不影响同一订单的其他连接。 */
    public void remove(String connectionId) {
        Connection removed = connections.remove(connectionId);
        if (removed == null) {
            return;
        }
        if (removed.bookingId() != null) {
            removeId(byBooking, removed.bookingId(), connectionId);
        }
        else {
            removeId(userChannels, removed.userId(), connectionId);
        }
        removeId(byUser, removed.userId(), connectionId);
    }

    /**
     * 向连接在本实例上的某个订单的所有连接推送一条消息。
     * 发送失败的连接就地移除；返回成功送达的连接数。
     */
    public int send(Long bookingId, String name, Object payload) {
        int sent = 0;
        for (String connectionId : byBooking.getOrDefault(bookingId, Set.of())) {
            Connection connection = connections.get(connectionId);
            if (connection == null) {
                continue;
            }
            try {
                connection.emitter().send(SseEmitter.event().name(name)
                        .data(payload, MediaType.APPLICATION_JSON));
                sent++;
            }
            catch (Exception e) {
                remove(connectionId);
            }
        }
        return sent;
    }

    /** 向本实例上某用户的全部「用户级频道」连接推送刷新提醒。 */
    public int sendToUser(Long userId, String name, Object payload) {
        int sent = 0;
        for (String connectionId : userChannels.getOrDefault(userId, Set.of())) {
            Connection connection = connections.get(connectionId);
            if (connection == null) {
                continue;
            }
            try {
                connection.emitter().send(SseEmitter.event().name(name)
                        .data(payload, MediaType.APPLICATION_JSON));
                sent++;
            }
            catch (Exception e) {
                remove(connectionId);
            }
        }
        return sent;
    }

    /** 给所有连接发一次心跳，避免空闲连接被代理/负载均衡器掐断。返回剩余连接数。 */
    public int heartbeat() {
        for (String connectionId : connections.keySet()) {
            Connection connection = connections.get(connectionId);
            if (connection == null) {
                continue;
            }
            try {
                connection.emitter().send(SseEmitter.event().comment("ping"));
            }
            catch (Exception e) {
                remove(connectionId);
            }
        }
        return connections.size();
    }

    /** API 停机时主动关闭本实例的全部连接，让浏览器尽快重连到其他实例。 */
    public int closeAll() {
        int closed = connections.size();
        connections.keySet().forEach(connectionId -> {
            Connection connection = connections.get(connectionId);
            if (connection == null) {
                return;
            }
            try {
                connection.emitter().complete();
            }
            catch (Exception ignored) {
                // 连接可能已经断开；无论如何都会从注册表里删掉。
            }
            remove(connectionId);
        });
        return closed;
    }

    public int size() {
        return connections.size();
    }

    public int countByBooking(Long bookingId) {
        return byBooking.getOrDefault(bookingId, Set.of()).size();
    }

    public int countByUser(Long userId) {
        return byUser.getOrDefault(userId, Set.of()).size();
    }

    private static void removeId(Map<Long, Set<String>> index, Long key, String connectionId) {
        Set<String> ids = index.get(key);
        if (ids == null) {
            return;
        }
        ids.remove(connectionId);
        if (ids.isEmpty()) {
            index.remove(key, ids);
        }
    }
}
