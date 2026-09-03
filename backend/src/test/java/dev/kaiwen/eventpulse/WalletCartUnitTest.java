package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.controller.CartController;
import dev.kaiwen.eventpulse.controller.WalletController;
import dev.kaiwen.eventpulse.dto.CartDtos.AddCartItemRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CartItemVo;
import dev.kaiwen.eventpulse.dto.CartDtos.CartVo;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutItemRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutVo;
import dev.kaiwen.eventpulse.dto.CartDtos.UpdateCartItemRequest;
import dev.kaiwen.eventpulse.dto.WalletDtos.LedgerVo;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Checkout;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.repository.WalletLedgerRepository;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.CartService;
import dev.kaiwen.eventpulse.service.CheckoutService;
import dev.kaiwen.eventpulse.service.WalletService;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;

@ExtendWith(MockitoExtension.class)
class WalletCartUnitTest {

    @Mock
    WalletLedgerRepository ledgers;
    @Mock
    OutboxWriter outbox;
    @Mock
    CartService cartService;
    @Mock
    CheckoutService checkoutService;
    @Mock
    BookingService bookingService;

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @Test
    void walletLedgerEntityAccessors() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        WalletLedger ledger = new WalletLedger();
        ledger.setId(1L);
        ledger.setUserId(2L);
        ledger.setBizType(WalletLedger.TYPE_RECHARGE);
        ledger.setAmountCents(300);
        ledger.setBalanceBeforeCents(100);
        ledger.setBalanceAfterCents(400);
        ledger.setBookingId(3L);
        ledger.setCheckoutId(4L);
        ledger.setExternalBizId("RECHARGE:x");
        ledger.setDescription("d");
        ledger.setSeqNo(5);
        ledger.setCreatedAt(now);
        assertThat(ledger.getId()).isEqualTo(1L);
        assertThat(ledger.getUserId()).isEqualTo(2L);
        assertThat(ledger.getBizType()).isEqualTo(WalletLedger.TYPE_RECHARGE);
        assertThat(ledger.getAmountCents()).isEqualTo(300);
        assertThat(ledger.getBalanceBeforeCents()).isEqualTo(100);
        assertThat(ledger.getBalanceAfterCents()).isEqualTo(400);
        assertThat(ledger.getBookingId()).isEqualTo(3L);
        assertThat(ledger.getCheckoutId()).isEqualTo(4L);
        assertThat(ledger.getExternalBizId()).isEqualTo("RECHARGE:x");
        assertThat(ledger.getDescription()).isEqualTo("d");
        assertThat(ledger.getSeqNo()).isEqualTo(5);
        assertThat(ledger.getCreatedAt()).isEqualTo(now);
        assertThat(WalletLedger.TYPE_BOOKING_PAYMENT).isEqualTo("BOOKING_PAYMENT");
        assertThat(WalletLedger.TYPE_BOOKING_REFUND).isEqualTo("BOOKING_REFUND");
        assertThat(WalletLedger.TYPE_EVENT_CANCEL_REFUND).isEqualTo("EVENT_CANCEL_REFUND");
        assertThat(WalletLedger.TYPE_OPENING_BALANCE).isEqualTo("OPENING_BALANCE");
    }

    @Test
    void checkoutEntityAccessors() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        Checkout checkout = new Checkout();
        checkout.setId(1L);
        checkout.setUserId(2L);
        checkout.setIdempotencyKey("key");
        checkout.setRequestHash("hash");
        checkout.setStatus(Checkout.STATUS_SUCCEEDED);
        checkout.setCreatedAt(now);
        assertThat(checkout.getId()).isEqualTo(1L);
        assertThat(checkout.getUserId()).isEqualTo(2L);
        assertThat(checkout.getIdempotencyKey()).isEqualTo("key");
        assertThat(checkout.getRequestHash()).isEqualTo("hash");
        assertThat(checkout.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(checkout.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void walletServiceLedgerPaginationMapsRows() {
        WalletService wallets = new WalletService(null, ledgers, outbox);
        WalletLedger row = new WalletLedger();
        row.setId(9L);
        row.setBizType(WalletLedger.TYPE_BOOKING_REFUND);
        row.setAmountCents(1200);
        row.setBalanceBeforeCents(0);
        row.setBalanceAfterCents(1200);
        row.setBookingId(7L);
        row.setCheckoutId(8L);
        row.setDescription("refund");
        row.setSeqNo(2);
        row.setCreatedAt(Instant.EPOCH);
        when(ledgers.searchCount(2L, "BOOKING_REFUND", null, null)).thenReturn(1L);
        when(ledgers.searchCount(eq(2L), isNull(), isNull(), isNull())).thenReturn(1L);
        when(ledgers.searchPage(2L, "BOOKING_REFUND", null, null, 10, 0)).thenReturn(List.of(row));

        PageResult<LedgerVo> page = wallets.ledger(2L, "BOOKING_REFUND", null, null, 0, 10);
        assertThat(page.getTotal()).isEqualTo(1);
        LedgerVo vo = page.getRecords().get(0);
        assertThat(vo.id()).isEqualTo(9L);
        assertThat(vo.amountCents()).isEqualTo(1200);
        assertThat(vo.bookingId()).isEqualTo(7L);
        assertThat(vo.seqNo()).isEqualTo(2);
        assertThat(wallets.ledger(2L, " ", null, null, -1, 0).getTotal()).isEqualTo(1);
    }

    @Test
    void cartAndWalletControllersWireRequests() {
        WalletService wallets = new WalletService(null, ledgers, outbox);
        WalletController walletApi = new WalletController(wallets);
        when(ledgers.searchCount(any(), any(), any(), any())).thenReturn(1L);
        when(ledgers.searchPage(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        BaseContext.setUserId(2L);
        assertThat(walletApi.ledger(null, null, null, 0, 10).getData().getTotal()).isEqualTo(1L);

        CartItemVo item = new CartItemVo(1L, 1L, "t", "PUBLISHED", Instant.EPOCH, 1,
                100, 100, 100, true, 10, 5, List.of());
        CartVo cart = new CartVo(List.of(item), 100, false);
        when(cartService.view()).thenReturn(cart);
        when(cartService.add(any(), anyInt())).thenReturn(cart);
        when(cartService.update(any(), any(), any())).thenReturn(cart);
        when(cartService.remove(any())).thenReturn(cart);
        when(cartService.clear()).thenReturn(cart);
        when(cartService.refreshPrices()).thenReturn(cart);
        CartController cartApi = new CartController(cartService, checkoutService, bookingService);
        assertThat(cartApi.view().getData().items()).hasSize(1);
        assertThat(cartApi.add(new AddCartItemRequest(1L, 2)).getData().items()).hasSize(1);
        assertThat(cartApi.update(1L, new UpdateCartItemRequest(2, true)).getData().items()).hasSize(1);
        assertThat(cartApi.remove(1L).getData().items()).hasSize(1);
        assertThat(cartApi.clear().getData().items()).hasSize(1);
        assertThat(cartApi.refreshPrices().getData().selectedTotalCents()).isEqualTo(100);

        // 结算：Settlement → CheckoutVo 装配
        Booking booking = new Booking();
        booking.setId(31L);
        booking.setUserId(2L);
        booking.setEventId(1L);
        booking.setQuantity(1);
        booking.setPaidCents(100);
        booking.setCheckoutId(77L);
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(Instant.EPOCH);
        when(checkoutService.settleCart(2L, "key", List.of(new CheckoutItemRequest(1L, 1))))
                .thenReturn(new CheckoutService.Settlement(77L, List.of(booking), false));
        CheckoutVo vo = cartApi.checkout(new CheckoutRequest(List.of(new CheckoutItemRequest(1L, 1))), "key").getData();
        assertThat(vo.checkoutId()).isEqualTo(77L);
        assertThat(vo.totalPaidCents()).isEqualTo(100);

        // 非法时间参数 → 400
        BaseContext.setRole("USER");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> walletApi.ledger(null, null, "not-a-time", null, null))
                .isInstanceOf(dev.kaiwen.eventpulse.exception.BusinessException.class)
                .extracting(ex -> ((dev.kaiwen.eventpulse.exception.BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
