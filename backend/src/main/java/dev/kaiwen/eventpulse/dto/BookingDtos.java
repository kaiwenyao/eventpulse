package dev.kaiwen.eventpulse.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateBookingRequest(
            @NotNull Long eventId,
            @Min(1) @Max(10) int quantity) {
    }

    /**
     * 订单视图：金额一律来自订单快照（unitPriceCents / paidCents），
     * 绝不按当前活动价重算；refundCents 是已退款金额（取消订单 = paidCents）。
     * cancellable / cancelBlockReason 是机器键，前端负责 i18n；
     * 活动状态 / 票据状态是独立字段，不会被写成新的支付状态。
     */
    public record BookingVo(
            Long id,
            Long eventId,
            String eventTitle,
            String eventStatus,
            Instant eventStartsAt,
            int quantity,
            Long unitPriceCents,
            long paidCents,
            String status,
            Instant createdAt,
            Instant cancelledAt,
            String organiserNote,
            long checkedInCount,
            long validCount,
            long refundCents,
            Long refundLedgerId,
            Long checkoutId,
            Boolean cancellable,
            String cancelBlockReason,
            List<RelatedBookingVo> relatedBookings) {
    }

    /** 同一次购物车结算的其他订单（详情页展示关联；列表页为 null）。 */
    public record RelatedBookingVo(Long id, Long eventId, String eventTitle, int quantity, long paidCents) {
    }

    public record NotificationVo(
            Long id,
            Long userId,
            Long eventId,
            Long bookingId,
            String type,
            String title,
            String message,
            String payload,
            Instant readAt,
            Instant createdAt) {
    }

    public record CheckInRequest(String code, String source) {
    }

    public record UndoCheckInRequest(String reason) {
    }
}
