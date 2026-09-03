package dev.kaiwen.eventpulse.dto;

import java.time.Instant;

public final class WalletDtos {

    private WalletDtos() {
    }

    /** 余额流水：金额带正负号，前后余额用于核对流水链。 */
    public record LedgerVo(
            Long id,
            String bizType,
            long amountCents,
            long balanceBeforeCents,
            long balanceAfterCents,
            Long bookingId,
            Long checkoutId,
            String description,
            long seqNo,
            Instant createdAt) {
    }
}
