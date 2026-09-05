package dev.kaiwen.eventpulse.kafka;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * wallet-events 消息体：引用一条已经落库的 wallet_ledger 流水。
 * 消费者只能据此发提醒 / 统计，绝不能再次修改余额或生成核心流水。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletEvent(
        String messageId,
        String eventType,
        Integer schemaVersion,
        String occurredAt,
        Long userId,
        Long ledgerId,
        Long seqNo,
        String bizType,
        Long amountCents,
        Long balanceBeforeCents,
        Long balanceAfterCents,
        Long bookingId,
        Long checkoutId,
        String dedupKey) {

    public boolean valid() {
        return dedupKey != null && !dedupKey.isBlank()
                && eventType != null && !eventType.isBlank()
                && userId != null
                && ledgerId != null
                && bizType != null && !bizType.isBlank()
                && amountCents != null;
    }
}
