package dev.kaiwen.eventpulse.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.exception.BusinessException;
import org.springframework.web.servlet.mvc.method.annotation.CapturingEmitterHandler;

class SseConnectionRegistryTest {

    private final SseConnectionRegistry registry = new SseConnectionRegistry(60_000, 2, 3);

    private static void connect(SseEmitter emitter) {
        new CapturingEmitterHandler().attachTo(emitter);
    }

    private static void connectBroken(SseEmitter emitter) {
        CapturingEmitterHandler.broken().attachTo(emitter);
    }

    @Test
    void registerReturnsEmitterAndTracksConnection() {
        SseEmitter emitter = registry.register(1L, 10L);
        connect(emitter);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.countByBooking(1L)).isEqualTo(1);
        assertThat(registry.countByUser(10L)).isEqualTo(1);
    }

    @Test
    void bookingConnectionLimitIsEnforced() {
        connect(registry.register(1L, 10L));
        connect(registry.register(1L, 10L));
        // 同一订单最多 2 条连接（多个标签页都有效，但不能无限增长）。
        assertThatThrownBy(() -> registry.register(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many live connections");
    }

    @Test
    void userConnectionLimitIsEnforcedAcrossBookings() {
        for (long bookingId = 1; bookingId <= 3; bookingId++) {
            connect(registry.register(bookingId, 10L));
        }
        assertThatThrownBy(() -> registry.register(4L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many live connections");
    }

    @Test
    void completionRemovesOnlyItsOwnConnection() {
        SseEmitter first = registry.register(1L, 10L);
        connect(first);
        connect(registry.register(1L, 10L));
        first.complete();
        // 只删除自己的连接；同一订单的其他连接（其他标签页）不受影响。
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.countByBooking(1L)).isEqualTo(1);
    }

    @Test
    void sendFailureRemovesBrokenConnection() {
        connectBroken(registry.register(1L, 10L));
        int sent = registry.send(1L, "reminder", new SseReminder("e1", "BOOKING_UPDATED", 1L, "now"));
        assertThat(sent).isZero();
        assertThat(registry.size()).isZero();
    }

    @Test
    void sendReachesEveryConnectionOfTheBooking() {
        connect(registry.register(1L, 10L));
        connect(registry.register(1L, 11L));
        int sent = registry.send(1L, "reminder", new SseReminder("e1", "BOOKING_UPDATED", 1L, "now"));
        assertThat(sent).isEqualTo(2);
    }

    @Test
    void heartbeatKeepsHealthyAndDropsBrokenConnections() {
        connect(registry.register(1L, 10L));
        connectBroken(registry.register(2L, 11L));
        int remaining = registry.heartbeat();
        assertThat(remaining).isEqualTo(1);
        assertThat(registry.countByBooking(2L)).isZero();
    }

    @Test
    void closeAllClosesEveryConnectionAndClearsState() {
        connect(registry.register(1L, 10L));
        connect(registry.register(2L, 11L));
        int closed = registry.closeAll();
        assertThat(closed).isEqualTo(2);
        assertThat(registry.size()).isZero();
        assertThat(registry.countByBooking(1L)).isZero();
        assertThat(registry.countByUser(10L)).isZero();
    }

    @Test
    void sendToBookingWithoutConnectionsIsNoOp() {
        assertThat(registry.send(99L, "reminder", new SseReminder("e1", "X", 99L, "now"))).isZero();
    }

    @Test
    void removeOfUnknownConnectionIsNoOp() {
        registry.remove("does-not-exist");
        assertThat(registry.size()).isZero();
    }
}
