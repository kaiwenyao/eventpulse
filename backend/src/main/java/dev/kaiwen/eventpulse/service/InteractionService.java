package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import dev.kaiwen.eventpulse.entity.Interaction;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;

/**
 * 用户行为记录（interactions + event_daily_metrics）。
 * 页面行为（VIEW / CLICK / SAVE / UNSAVE）与 Kafka 消费（BOOK / CANCEL）共用。
 *
 * 方法不开启新的独立事务，而是加入调用方已有的事务：
 * BookingConsumer 在同一事务里需要通知、互动与每日统计一起成功或一起回滚。
 */
@Service
public class InteractionService {

    private final InteractionRepository interactions;
    private final EventDailyMetricRepository metrics;

    public InteractionService(InteractionRepository interactions, EventDailyMetricRepository metrics) {
        this.interactions = interactions;
        this.metrics = metrics;
    }

    /**
     * 记录一次行为。type 由调用方决定；页面只能提交 VIEW / CLICK / SAVE / UNSAVE，
     * Kafka 消费者提交 BOOK / CANCEL（数据来自已完成的后端事务，不是客户端自报）。
     */
    public void record(Long userId, Long eventId, String type) {
        Interaction interaction = new Interaction();
        interaction.setUserId(userId);
        interaction.setEventId(eventId);
        interaction.setType(type);
        interaction.setCreatedAt(Instant.now());
        interactions.save(interaction);
        incrementDailyMetric(eventId, type);
    }

    private void incrementDailyMetric(Long eventId, String type) {
        LocalDate today = LocalDate.now();
        // 数据库直接原子加一，避免并发读取同一个旧值后互相覆盖。
        switch (type) {
            case "VIEW" -> metrics.incrementViews(eventId, today);
            case "CLICK" -> metrics.incrementClicks(eventId, today);
            case "SAVE" -> metrics.incrementSaves(eventId, today);
            case "UNSAVE" -> metrics.incrementUnsaves(eventId, today);
            case "BOOK" -> metrics.incrementBookings(eventId, today);
            case "CANCEL" -> metrics.incrementCancels(eventId, today);
            default -> {
            }
        }
    }
}