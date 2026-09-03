package dev.kaiwen.eventpulse.outbox;

/**
 * 事件 Topic 常量：Relay 与 Consumer 共用，避免魔法字符串分散。
 */
public final class KafkaTopics {

    public static final String BOOKING_EVENTS = "booking-events";
    public static final String BOOKING_EVENTS_DLT = "booking-events.DLT";

    /** 购物车变更公告（已提交变更的说明，不是异步执行命令）。按用户分区。 */
    public static final String CART_EVENTS = "cart-events";
    public static final String CART_EVENTS_DLT = "cart-events.DLT";

    /** 钱包流水已记账公告（引用已落库的 wallet_ledger 行）。按用户分区。 */
    public static final String WALLET_EVENTS = "wallet-events";
    public static final String WALLET_EVENTS_DLT = "wallet-events.DLT";

    private KafkaTopics() {
    }
}
