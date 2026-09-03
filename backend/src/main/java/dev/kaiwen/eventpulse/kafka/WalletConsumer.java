package dev.kaiwen.eventpulse.kafka;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.sse.SseReminderPublisher;

/**
 * 消费 wallet-events（独立 consumer group「eventpulse-wallet」），在一个数据库事务里：
 *  1. consumed_events 去重（重复投递直接结束）；
 *  2. RECHARGE 创建站内通知（充值没有其他通知来源）；
 *     BOOKING_PAYMENT / 退款不建通知——订单创建 / 取消通知由 booking-events
 *     消费链负责，避免同一次支付或退款出现重复、含义相同的提醒；
 *  3. 注册事务提交后的用户级 SSE 提醒（WALLET_CHANGED → 刷新余额与流水页）。
 *
 * 消费者绝不根据事件修改余额或生成核心流水：wallet_ledger 与 users.wallet_cents
 * 已经在业务事务里一致地提交，事件只是「已记账」的公告。
 */
@Component
@Profile("worker")
public class WalletConsumer {

    public static final String CONSUMER_GROUP = "eventpulse-wallet";

    private final ConsumedEventRepository consumedEvents;
    private final NotificationRepository notifications;
    private final SseReminderPublisher reminders;
    private final ObjectMapper objectMapper;

    public WalletConsumer(
            ConsumedEventRepository consumedEvents,
            NotificationRepository notifications,
            SseReminderPublisher reminders,
            ObjectMapper objectMapper) {
        this.consumedEvents = consumedEvents;
        this.notifications = notifications;
        this.reminders = reminders;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.WALLET_EVENTS, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json) {
        WalletEvent event = parse(json);
        if (!event.valid()) {
            throw new IllegalStateException("Wallet message missing required fields: " + json);
        }

        boolean firstTime = consumedEvents.tryInsert(CONSUMER_GROUP, event.dedupKey()) == 1;
        if (!firstTime) {
            return;
        }

        if (WalletLedger.TYPE_RECHARGE.equals(event.bizType())) {
            Notification notice = new Notification(null, rechargeMessage(event));
            notice.setUserId(event.userId());
            notice.setType("WALLET_RECHARGED");
            notice.setTitle("Wallet recharged");
            notice.setDedupKey(event.dedupKey());
            notifications.save(notice);
        }

        // 退款 / 支付不重复建通知；只提醒用户刷新余额、流水与订单页面。
        // 消息 key 按 userId 分区且带 seqNo；不同 Topic 之间不假设全局顺序，
        // 页面数据一律以 REST 重新查询为准。
        reminders.remindUser(event.userId(), "WALLET_CHANGED", "sse:" + event.dedupKey());
    }

    private static String rechargeMessage(WalletEvent event) {
        long amount = event.amountCents() == null ? 0 : event.amountCents();
        return "Demo recharge of €" + String.format("%.2f", amount / 100.0)
                + " was added to your wallet";
    }

    private WalletEvent parse(String json) {
        try {
            return objectMapper.readValue(json, WalletEvent.class);
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to parse Kafka message: " + json, e);
        }
    }
}
