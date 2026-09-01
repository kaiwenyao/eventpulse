package dev.kaiwen.eventpulse.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.service.InteractionService;

/**
 * 消费 booking-events，在一个数据库事务里完成：
 *  1. 尝试写 consumed_events（幂等去重，插入 0 行说明以前完整处理过）；
 *  2. 创建通知；
 *  3. BOOKING_CREATED → 写 BOOK interaction，BOOKING_CANCELLED → 写 CANCEL
 *     interaction（EVENT_CANCELLED 等其他类型不写）；
 *  4. 整个事务提交。
 *
 * 任何一步失败（解析、保存、统计）都不能被静默吞掉：异常继续抛出，
 * 交给 Error Handler 有限重试，最终进入 DLT。
 */
@Component
public class BookingConsumer {

    public static final String CONSUMER_GROUP = "eventpulse";

    private final ConsumedEventRepository consumedEvents;
    private final NotificationRepository notifications;
    private final InteractionService interactionService;
    private final ObjectMapper objectMapper;

    public BookingConsumer(
            ConsumedEventRepository consumedEvents,
            NotificationRepository notifications,
            InteractionService interactionService,
            ObjectMapper objectMapper) {
        this.consumedEvents = consumedEvents;
        this.notifications = notifications;
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_EVENTS, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json) {
        BookingEvent event = parse(json);

        boolean firstTime = consumedEvents.tryInsert(CONSUMER_GROUP, event.dedupKey()) == 1;
        if (!firstTime) {
            // 以前已经完整处理过，重复投递直接结束。
            return;
        }

        notifications.save(toNotification(event));

        switch (event.type()) {
            case "BOOKING_CREATED" -> {
                requireInteractionFields(event);
                requirePositiveQuantity(event);
                interactionService.record(event.userId(), event.eventId(), "BOOK",
                        Math.toIntExact(event.quantity()));
            }
            case "BOOKING_CANCELLED" -> {
                requireInteractionFields(event);
                interactionService.record(event.userId(), event.eventId(), "CANCEL");
            }
            default -> {
                // 其他事件（如 EVENT_CANCELLED）只创建通知，不冒充用户主动预订 / 取消。
            }
        }
    }

    private BookingEvent parse(String json) {
        try {
            return objectMapper.readValue(json, BookingEvent.class);
        }
        catch (Exception e) {
            throw new IllegalStateException("无法解析 Kafka 消息: " + json, e);
        }
    }

    private static void requireInteractionFields(BookingEvent event) {
        if (!event.validForInteraction()) {
            throw new IllegalStateException("预订消息缺少 dedupKey / userId / eventId / bookingId: " + event);
        }
    }

    /**
     * BOOKING_CREATED 必须携带有效张数（> 0）。缺少或非法张数会写错 tickets 统计，
     * 直接抛出异常交给 Error Handler 进 DLT。
     */
    private static void requirePositiveQuantity(BookingEvent event) {
        if (event.quantity() == null || event.quantity() <= 0) {
            throw new IllegalStateException("BOOKING_CREATED 缺少有效 quantity（须 > 0）: " + event);
        }
    }

    private static Notification toNotification(BookingEvent event) {
        Notification notice = new Notification(
                event.bookingId(),
                event.message() == null || event.message().isBlank()
                        ? "已处理：" + event.type()
                        : event.message());
        notice.setUserId(event.userId());
        notice.setEventId(event.eventId());
        notice.setType(event.type());
        notice.setTitle(event.title() == null || event.title().isBlank() ? "消息通知" : event.title());
        notice.setDedupKey(event.dedupKey());
        return notice;
    }
}