package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;

/**
 * 验证 interaction 记录时张数正确流入每日统计：
 * 一次订 4 张，tickets 统计必须 +4（而不是固定 +1）。
 */
@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    @Mock
    InteractionRepository interactions;
    @Mock
    EventDailyMetricRepository metrics;

    private InteractionService service() {
        return new InteractionService(interactions, metrics);
    }

    @Test
    void bookWithQuantityAddsQuantityToTickets() {
        service().record(3L, 20L, "BOOK", 4);
        verify(metrics).incrementBookings(eq(20L), eq(LocalDate.now()), eq(4));
    }

    @Test
    void bookWithQuantityOneStillCountsOneTicket() {
        service().record(3L, 20L, "BOOK", 1);
        verify(metrics).incrementBookings(eq(20L), eq(LocalDate.now()), eq(1));
    }

    @Test
    void zeroQuantityIsRejected() {
        assertThatThrownBy(() -> service().record(3L, 20L, "BOOK", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        verify(interactions, never()).save(org.mockito.ArgumentMatchers.any());
        verify(metrics, never()).incrementBookings(eq(20L), eq(LocalDate.now()), eq(0));
    }

    @Test
    void negativeQuantityIsRejected() {
        assertThatThrownBy(() -> service().record(3L, 20L, "BOOK", -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        verify(interactions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pageBehavioursStillUseDefaultQuantity() {
        // 页面行为（VIEW/SAVE/UNSAVE/CANCEL）不带张数，走默认重载，bookings/tickets 不动。
        service().record(3L, 20L, "VIEW");
        verify(metrics).incrementViews(eq(20L), eq(LocalDate.now()));
        verify(metrics, never()).incrementBookings(eq(20L), eq(LocalDate.now()), org.mockito.ArgumentMatchers.anyInt());
    }
}
