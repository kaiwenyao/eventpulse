package dev.kaiwen.eventpulse.sse;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Worker 完成业务处理后，向 Redis 发布一条轻量「有新状态」提醒。
 * 所有 API 实例订阅同一频道，各自推给连接在自己身上的浏览器。
 *
 * 提醒必须在数据库事务提交成功后发送：由事务提交后的回调负责，
 * 事务回滚时浏览器不会收到「有变化」的假提醒。Redis 只是提醒通道，
 * 发布失败只记录日志，不影响业务数据（浏览器可通过 REST 补偿）。
 */
@Component
@Profile("worker")
public class SseReminderPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseReminderPublisher.class);

    private final ObjectMapper objectMapper;
    private StringRedisTemplate redis;

    public SseReminderPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 数据库事务内调用：注册 afterCommit 回调，提交成功后才真正发布。 */
    public void remindBooking(Long bookingId, String type, String eventId) {
        if (bookingId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(new SseReminder(eventId, type, bookingId, Instant.now().toString()));
                }
            });
        }
        else {
            publish(new SseReminder(eventId, type, bookingId, Instant.now().toString()));
        }
    }

    /**
     * 用户级刷新提醒（购物车 / 钱包 / 订单列表）：只发给该用户自己的频道连接。
     * 同样走 afterCommit；redisEventId 用于跨实例去重。
     */
    public void remindUser(Long userId, String type, String redisEventId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishUser(userId, type, redisEventId);
                }
            });
        }
        else {
            publishUser(userId, type, redisEventId);
        }
    }

    private void publishUser(Long userId, String type, String redisEventId) {
        String dedupEventId = redisEventId == null || redisEventId.isBlank()
                ? type + ":user:" + userId + ":" + UUID.randomUUID()
                : redisEventId;
        publish(new SseReminder(dedupEventId, type, null, userId, Instant.now().toString()));
    }

    private void publish(SseReminder reminder) {
        if (redis == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(reminder);
            redis.convertAndSend(SseReminder.REDIS_CHANNEL, json);
        }
        catch (Exception e) {
            log.warn("SSE 提醒发布失败（业务数据不受影响） type={} eventId={}", reminder.type(), reminder.eventId(), e);
        }
    }
}
