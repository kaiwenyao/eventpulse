package dev.kaiwen.eventpulse.dto;

import java.time.Instant;
import java.util.List;

import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class CartDtos {

    private CartDtos() {
    }

    /** 加入 / 合并购物车项。数量必须符合活动限购规则，由服务端校验。 */
    public record AddCartItemRequest(
            @NotNull Long eventId,
            @Min(1) @Max(99) int quantity) {
    }

    public record UpdateCartItemRequest(
            @Min(1) @Max(99) Integer quantity,
            Boolean selected) {
    }

    /** 单个购物车项视图：issues 是机器键列表（失效原因），前端 i18n。 */
    public record CartItemVo(
            Long id,
            Long eventId,
            String eventTitle,
            String eventStatus,
            Instant startsAt,
            int quantity,
            int unitPriceCents,
            int currentUnitPriceCents,
            long lineTotalCents,
            boolean selected,
            int maxQuantityPerBooking,
            int remaining,
            List<String> issues) {
    }

    public record CartVo(
            List<CartItemVo> items,
            long selectedTotalCents,
            boolean hasIssues) {
    }

    /** 购物车结算：一次结算勾选的多个活动；每个活动生成独立 Booking。 */
    public record CheckoutItemRequest(
            @NotNull Long itemId,
            @Min(1) @Max(99) int quantity) {
    }

    public record CheckoutRequest(@NotEmpty List<CheckoutItemRequest> items) {
    }

    /**
     * 结算结果：checkoutId 关联同次结算的全部订单；
     * reused=true 表示幂等键命中已成功的历史结算，bookings 是原订单。
     */
    public record CheckoutVo(
            Long checkoutId,
            boolean reused,
            List<BookingVo> bookings,
            long totalPaidCents) {
    }
}
