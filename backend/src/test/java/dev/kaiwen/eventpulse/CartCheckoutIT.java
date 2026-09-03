package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AuthDtos.WalletRechargeRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CartVo;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutItemRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.OutboxRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.AuthService;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.CartService;
import dev.kaiwen.eventpulse.service.CheckoutService;
import dev.kaiwen.eventpulse.service.OrganiserEventService;

/**
 * 购物车、批量结算与钱包流水的核心业务规则（真实 PostgreSQL 事务）：
 * 合并 / 限购 / 用户隔离、整次结算同进同退、价格变化重新确认、
 * 幂等键重试与并发、不超卖、余额链一致、取消与活动取消竞争不重复退款。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=api", "eventpulse.outbox.poll-ms=3600000"})
@Testcontainers(disabledWithoutDocker = true)
class CartCheckoutIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @Autowired
    CartService carts;
    @Autowired
    CheckoutService checkouts;
    @Autowired
    BookingService bookings;
    @Autowired
    OrganiserEventService organiserEvents;
    @Autowired
    AuthService auth;
    @Autowired
    EventRepository events;
    @Autowired
    UserRepository users;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactionTemplate;

    private static final AtomicLong SEQ = new AtomicLong();

    @AfterEach
    void clearContext() {
        BaseContext.clear();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private User newUser(String role, long walletCents) {
        User user = new User();
        user.setEmail("it-cart-" + SEQ.incrementAndGet() + "@test.dev");
        user.setPassword("x");
        user.setName("IT");
        user.setRole(role);
        user.setWalletCents(walletCents);
        return users.save(user);
    }

    private Event newEvent(Long organiserId, int priceCents, int capacity, String status) {
        Event event = new Event();
        event.setTitle("IT 购物车活动 " + SEQ.incrementAndGet());
        event.setDescription("it");
        event.setCategory("music");
        event.setCity("Berlin");
        event.setStartsAt(Instant.now().plus(7, ChronoUnit.DAYS));
        event.setEndsAt(Instant.now().plus(7, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        event.setPriceCents(priceCents);
        event.setCapacity(capacity);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(organiserId);
        event.setStatus(status);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        return events.save(event);
    }

    private void login(Long userId) {
        BaseContext.setUserId(userId);
        BaseContext.setRole("USER");
    }

    private static Long itemIdOf(CartVo cart, Long eventId) {
        return cart.items().stream()
                .filter(item -> item.eventId().equals(eventId))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private long walletOf(Long userId) {
        return jdbc.queryForObject("SELECT wallet_cents FROM users WHERE id = ?", Long.class, userId);
    }

    private List<Map<String, Object>> ledgerOf(Long userId) {
        return jdbc.queryForList(
                "SELECT * FROM wallet_ledger WHERE user_id = ? ORDER BY seq_no", userId);
    }

    /** 并发执行：每个任务在独立事务 + 独立 BaseContext 里跑。 */
    private <T> List<T> runConcurrently(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            List<Future<T>> futures = pool.invokeAll(jobs);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 购物车基础
    // ------------------------------------------------------------------

    @Test
    void cartMergesSameEventAndEnforcesLimitsAndIsolation() {
        User buyer = newUser("USER", 0);
        User other = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event event = newEvent(organiser.getId(), 1200, 100, EventStatus.PUBLISHED);

        login(buyer.getId());
        CartVo cart = carts.add(event.getId(), 2);
        assertThat(cart.items()).hasSize(1);
        cart = carts.add(event.getId(), 3);
        // 同一活动合并为一行
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(5);
        assertThat(cart.items().get(0).unitPriceCents()).isEqualTo(1200);

        // 数量必须符合活动限购（max 10）
        assertThatThrownBy(() -> carts.add(event.getId(), 6)).isInstanceOf(BusinessException.class);
        final Long mergedItemId = cart.items().get(0).id();
        assertThatThrownBy(() -> carts.update(mergedItemId, 11, null))
                .isInstanceOf(BusinessException.class);

        // 勾选 / 取消勾选、改数量
        cart = carts.update(cart.items().get(0).id(), 4, false);
        assertThat(cart.items().get(0).quantity()).isEqualTo(4);
        assertThat(cart.items().get(0).selected()).isFalse();
        assertThat(cart.selectedTotalCents()).isZero();

        // 用户隔离：另一用户看不到、也改不了别人的购物车项
        login(other.getId());
        assertThat(carts.view().items()).isEmpty();
        Long buyerItemId = cart.items().get(0).id();
        assertThatThrownBy(() -> carts.update(buyerItemId, 1, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> carts.remove(buyerItemId)).isInstanceOf(BusinessException.class);

        login(buyer.getId());
        cart = carts.remove(buyerItemId);
        assertThat(cart.items()).isEmpty();
        carts.add(event.getId(), 1);
        assertThat(carts.clear().items()).isEmpty();
    }

    @Test
    void cartShowsValidityReasonsAndPriceChangeRequiresConfirmation() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event cancelledEvent = newEvent(organiser.getId(), 500, 100, EventStatus.CANCELLED);
        Event soldOut = newEvent(organiser.getId(), 500, 0, EventStatus.PUBLISHED);
        soldOut.setSold(0);
        events.save(soldOut);
        // 容量 0 的活动 remaining = 0 → SOLD_OUT
        Event priced = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);

        login(buyer.getId());
        assertThatThrownBy(() -> carts.add(cancelledEvent.getId(), 1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> carts.add(soldOut.getId(), 1)).isInstanceOf(BusinessException.class);

        carts.add(priced.getId(), 2);
        // 价格变化：购物车展示 PRICE_CHANGED，结算被拒绝，绝不静默按新价扣款
        priced.setPriceCents(1500);
        events.save(priced);
        CartVo cart = carts.view();
        assertThat(cart.items().get(0).issues()).contains("PRICE_CHANGED");
        assertThat(cart.items().get(0).unitPriceCents()).isEqualTo(1000);
        assertThat(cart.items().get(0).currentUnitPriceCents()).isEqualTo(1500);
        assertThat(cart.hasIssues()).isTrue();
        final Long pricedItemId = cart.items().get(0).id();

        assertThatThrownBy(() -> checkouts.settleCart(buyer.getId(), "key-price-" + SEQ.get(),
                        List.of(new CheckoutItemRequest(pricedItemId, 2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Price");

        // 用户显式确认新价后才能按新价结算
        carts.refreshPrices();
        cart = carts.view();
        assertThat(cart.items().get(0).issues()).isEmpty();
        auth.recharge(buyer.getId(), new WalletRechargeRequest(50000), null);
        CheckoutService.Settlement result = checkouts.settleCart(buyer.getId(), "key-price2-" + SEQ.get(),
                List.of(new CheckoutItemRequest(cart.items().get(0).id(), 2)));
        assertThat(result.totalPaidCents()).isEqualTo(3000);
        assertThat(walletOf(buyer.getId())).isEqualTo(50000 - 3000);
    }

    // ------------------------------------------------------------------
    // 批量结算：整次成功 / 整次回滚
    // ------------------------------------------------------------------

    @Test
    void multiEventCheckoutSettlesTogetherWithLedgerPerBooking() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);
        Event b = newEvent(organiser.getId(), 2500, 100, EventStatus.PUBLISHED);

        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(10000), null);
        carts.add(a.getId(), 2);
        carts.add(b.getId(), 1);
        CartVo cart = carts.view();
        assertThat(cart.selectedTotalCents()).isEqualTo(4500);

        long outboxBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM outbox", Long.class);
        Long itemA = itemIdOf(cart, a.getId());
        Long itemB = itemIdOf(cart, b.getId());
        CheckoutService.Settlement result = checkouts.settleCart(buyer.getId(), "checkout-multi-" + SEQ.get(),
                List.of(new CheckoutItemRequest(itemA, 2), new CheckoutItemRequest(itemB, 1)));

        // 每个活动一张独立订单，checkoutId 关联
        assertThat(result.bookings()).hasSize(2);
        assertThat(result.totalPaidCents()).isEqualTo(4500);
        assertThat(result.checkoutId()).isNotNull();
        for (dev.kaiwen.eventpulse.entity.Booking booking : result.bookings()) {
            assertThat(booking.getCheckoutId()).isEqualTo(result.checkoutId());
            assertThat(booking.getPaidCents()).isEqualTo(booking.getUnitPriceCents() * booking.getQuantity());
        }

        // 购物车已购项被移除
        assertThat(carts.view().items()).isEmpty();
        // 余额与流水一致：充值 + 两笔扣款
        assertThat(walletOf(buyer.getId())).isEqualTo(10000 - 4500);
        List<Map<String, Object>> ledger = ledgerOf(buyer.getId());
        assertThat(ledger).hasSize(3);
        long running = 0;
        for (Map<String, Object> row : ledger) {
            long before = ((Number) row.get("balance_before_cents")).longValue();
            long amount = ((Number) row.get("amount_cents")).longValue();
            long after = ((Number) row.get("balance_after_cents")).longValue();
            assertThat(before + amount).isEqualTo(after);
            running = after;
        }
        assertThat(running).isEqualTo(10000 - 4500);
        // 每张订单一笔扣款流水，且都带 checkoutId
        List<Map<String, Object>> payments = jdbc.queryForList(
                "SELECT * FROM wallet_ledger WHERE user_id = ? AND biz_type = 'BOOKING_PAYMENT'",
                buyer.getId());
        assertThat(payments).hasSize(2);
        payments.forEach(row -> assertThat(row.get("checkout_id")).isEqualTo(result.checkoutId()));

        // 同事务写出的 Outbox：2 booking + 2 wallet + 1 cart 汇总
        List<Map<String, Object>> newOutbox = jdbc.queryForList(
                "SELECT topic, event_type FROM outbox WHERE id > ? ORDER BY id",
                outboxBefore);
        assertThat(newOutbox).extracting(row -> row.get("event_type"))
                .contains("BOOKING_CREATED", "BOOKING_CREATED", "WALLET_LEDGER_RECORDED",
                        "WALLET_LEDGER_RECORDED", "CART_CHECKOUT_COMPLETED");

        // 电子票已出
        for (dev.kaiwen.eventpulse.entity.Booking booking : result.bookings()) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Long.class, booking.getId()))
                    .isEqualTo(booking.getQuantity());
        }
    }

    @Test
    void checkoutOnlySettlesRequestedItems() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);
        Event b = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(3000), null);
        CartVo cart = carts.add(a.getId(), 1);
        cart = carts.add(b.getId(), 1);
        // 未勾选第二项：只结算第一项
        cart = carts.update(cart.items().get(1).id(), null, false);
        CheckoutService.Settlement result = checkouts.settleCart(buyer.getId(), "checkout-partial-" + SEQ.get(),
                List.of(new CheckoutItemRequest(cart.items().get(0).id(), 1)));
        assertThat(result.bookings()).hasSize(1);
        CartVo after = carts.view();
        assertThat(after.items()).hasSize(1);
        assertThat(after.items().get(0).eventId()).isEqualTo(b.getId());
        assertThat(walletOf(buyer.getId())).isEqualTo(2000);
    }

    @Test
    void insufficientBalanceRollsBackTheWholeCheckout() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 4000, 100, EventStatus.PUBLISHED);
        Event b = newEvent(organiser.getId(), 4000, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(5000), null);
        CartVo cart = carts.add(a.getId(), 1);
        carts.add(b.getId(), 1);
        long soldA = events.findById(a.getId()).orElseThrow().getSold();
        long outboxBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM outbox", Long.class);

        assertThatThrownBy(() -> checkouts.settleCart(buyer.getId(), "checkout-broke-" + SEQ.get(),
                        List.of(new CheckoutItemRequest(itemIdOf(carts.view(), a.getId()), 1),
                                new CheckoutItemRequest(itemIdOf(carts.view(), b.getId()), 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        // 整次回滚：购物车原样保留、余额未动、库存恢复、无订单无流水无 Outbox
        assertThat(carts.view().items()).hasSize(2);
        assertThat(walletOf(buyer.getId())).isEqualTo(5000);
        assertThat(events.findById(a.getId()).orElseThrow().getSold()).isEqualTo(soldA);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE user_id = ?", Long.class, buyer.getId())).isZero();
        assertThat(ledgerOf(buyer.getId())).hasSize(1); // 只有充值
        // 失败事务不留下任何可投递事件（按 id 增量查询，不受共享库上其他测试影响）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE id > ? AND event_type IN ('BOOKING_CREATED','CART_CHECKOUT_COMPLETED')",
                Long.class, outboxBefore)).isZero();
        // 结算失败不留幂等键：同一键可以重新结算
        auth.recharge(buyer.getId(), new WalletRechargeRequest(5000), null);
        CheckoutService.Settlement result = checkouts.settleCart(buyer.getId(), "checkout-broke-" + SEQ.get(),
                List.of(new CheckoutItemRequest(carts.view().items().get(0).id(), 1)));
        assertThat(result.bookings()).hasSize(1);
    }

    @Test
    void soldOutDuringCheckoutRollsBackEverything() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event tight = newEvent(organiser.getId(), 100, 3, EventStatus.PUBLISHED);
        Event filler = newEvent(organiser.getId(), 100, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(10000), null);
        CartVo cart = carts.add(tight.getId(), 2);
        carts.add(filler.getId(), 1);
        final Long tightItemId = itemIdOf(cart, tight.getId());
        final Long fillerItemId = itemIdOf(carts.view(), filler.getId());
        // 让 tight 活动只剩 1 张票：另一用户先买走 2 张
        User rival = newUser("USER", 10000);
        login(rival.getId());
        bookings.create(new CreateBookingRequest(tight.getId(), 2), null);
        login(buyer.getId());

        assertThatThrownBy(() -> checkouts.settleCart(buyer.getId(), "checkout-tight-" + SEQ.get(),
                        List.of(new CheckoutItemRequest(tightItemId, 2),
                                new CheckoutItemRequest(fillerItemId, 1))))
                .isInstanceOf(BusinessException.class);

        // 整次回滚：filler 也没有卖出
        assertThat(events.findById(filler.getId()).orElseThrow().getSold()).isZero();
        assertThat(carts.view().items()).hasSize(2);
        assertThat(walletOf(buyer.getId())).isEqualTo(10000);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotentKeyRetryReturnsOriginalBookingsEvenAfterCartCleared() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(5000), null);
        CartVo cart = carts.add(a.getId(), 2);
        String key = "checkout-idem-" + SEQ.get();

        CheckoutService.Settlement first = checkouts.settleCart(buyer.getId(), key,
                List.of(new CheckoutItemRequest(cart.items().get(0).id(), 2)));
        carts.clear();

        // 已成功结算后的重试：购物车已空，仍返回原订单
        CheckoutService.Settlement retry = checkouts.settleCart(buyer.getId(), key,
                List.of(new CheckoutItemRequest(cart.items().get(0).id(), 2)));
        assertThat(retry.checkoutId()).isEqualTo(first.checkoutId());
        assertThat(retry.bookings()).extracting(dev.kaiwen.eventpulse.entity.Booking::getId)
                .containsExactlyElementsOf(first.bookings().stream()
                        .map(dev.kaiwen.eventpulse.entity.Booking::getId).toList());

        // 只扣了一次款
        assertThat(walletOf(buyer.getId())).isEqualTo(3000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE user_id = ?", Long.class, buyer.getId())).isEqualTo(1);

        // 同一键配不同参数 → 拒绝
        assertThatThrownBy(() -> checkouts.settleCart(buyer.getId(), key,
                List.of(new CheckoutItemRequest(cart.items().get(0).id(), 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void concurrentDuplicateCheckoutSettlesExactlyOnce() throws Exception {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 1000, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(5000), null);
        CartVo cart = carts.add(a.getId(), 1);
        Long itemId = cart.items().get(0).id();
        String key = "checkout-race-" + SEQ.get();

        List<CheckoutService.Settlement> results = runConcurrently(List.of(
                () -> withLogin(buyer.getId(), () -> checkouts.settleCart(buyer.getId(), key,
                        List.of(new CheckoutItemRequest(itemId, 1)))),
                () -> withLogin(buyer.getId(), () -> checkouts.settleCart(buyer.getId(), key,
                        List.of(new CheckoutItemRequest(itemId, 1))))));

        // 两个请求都拿到结果，但只有一张订单、一次扣款
        long distinctBookings = results.stream()
                .flatMap(r -> r.bookings().stream())
                .map(dev.kaiwen.eventpulse.entity.Booking::getId)
                .distinct()
                .count();
        assertThat(distinctBookings).isEqualTo(1);
        assertThat(walletOf(buyer.getId())).isEqualTo(4000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND biz_type = 'BOOKING_PAYMENT'",
                Long.class, buyer.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：不超卖、钱包一致性、双重退款
    // ------------------------------------------------------------------

    @Test
    void concurrentUsersNeverOversellTheLastTickets() throws Exception {
        User organiser = newUser("ORGANISER", 0);
        Event tight = newEvent(organiser.getId(), 100, 5, EventStatus.PUBLISHED);
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            buyers.add(newUser("USER", 10000));
        }
        List<Long> ids = runConcurrently(buyers.stream().<Callable<Long>>map(buyer -> () ->
                withLogin(buyer.getId(), () -> {
                    try {
                        return bookings.create(new CreateBookingRequest(tight.getId(), 1), null).id();
                    }
                    catch (BusinessException e) {
                        return null; // 抢空的请求按预期失败
                    }
                })).toList());
        assertThat(ids).hasSize(12);
        assertThat(ids.stream().filter(java.util.Objects::nonNull).distinct().count()).isEqualTo(5);
        int sold = events.findById(tight.getId()).orElseThrow().getSold();
        assertThat(sold).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE event_id = ?", Long.class, tight.getId())).isEqualTo(5);
    }

    @Test
    void concurrentRechargeDebitRefundKeepLedgerChainConsistent() throws Exception {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 300, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        // 先顺序充值 / 下单，流水链从 0 开始可核对；再并发叠加操作。
        auth.recharge(buyer.getId(), new WalletRechargeRequest(5000), null);
        Long firstBookingId = bookings.create(new CreateBookingRequest(a.getId(), 1), null).id();

        List<Callable<Object>> jobs = new ArrayList<>();
        jobs.add(() -> withLogin(buyer.getId(), () -> auth.recharge(buyer.getId(), new WalletRechargeRequest(700), null)));
        jobs.add(() -> withLogin(buyer.getId(), () -> auth.recharge(buyer.getId(), new WalletRechargeRequest(200), null)));
        jobs.add(() -> withLogin(buyer.getId(), () -> bookings.cancel(firstBookingId)));
        runConcurrently(jobs);

        // 流水链：按 seq 顺序 before + amount = after，最终余额与 users.wallet_cents 一致
        List<Map<String, Object>> ledger = ledgerOf(buyer.getId());
        long running = 0;
        long lastSeq = 0;
        for (Map<String, Object> row : ledger) {
            long seq = ((Number) row.get("seq_no")).longValue();
            assertThat(seq).isGreaterThan(lastSeq);
            lastSeq = seq;
            long before = ((Number) row.get("balance_before_cents")).longValue();
            long amount = ((Number) row.get("amount_cents")).longValue();
            long after = ((Number) row.get("balance_after_cents")).longValue();
            assertThat(before).isEqualTo(running);
            assertThat(before + amount).isEqualTo(after);
            running = after;
        }
        assertThat(running).isEqualTo(walletOf(buyer.getId()));
        // 退款只有一笔
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND biz_type = 'BOOKING_REFUND'",
                Long.class, buyer.getId())).isEqualTo(1);
    }

    @Test
    void rechargeIdempotencyIsUserScopedAndResponseCarriesFreshBalance() {
        User alice = newUser("USER", 0);
        User bob = newUser("USER", 0);
        login(alice.getId());

        // 响应必须携带本次充值后的新余额：WalletService 用 JdbcTemplate 更新余额，
        // 一级缓存里的 User 旧快照不能把它带回答复（回归测试）。
        assertThat(auth.recharge(alice.getId(), new WalletRechargeRequest(500), "shared-key").walletCents())
                .isEqualTo(500);

        // 同键同金额重试：幂等重放，不重复入账。
        assertThat(auth.recharge(alice.getId(), new WalletRechargeRequest(500), "shared-key").walletCents())
                .isEqualTo(500);
        assertThat(ledgerOf(alice.getId())).hasSize(1);

        // 同键不同金额：拒绝，不入账。
        assertThatThrownBy(() -> auth.recharge(alice.getId(), new WalletRechargeRequest(300), "shared-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different amount");
        assertThat(walletOf(alice.getId())).isEqualTo(500);
        assertThat(ledgerOf(alice.getId())).hasSize(1);

        // 另一用户用相同的键：各自成功，不互相吞掉充值。
        login(bob.getId());
        assertThat(auth.recharge(bob.getId(), new WalletRechargeRequest(700), "shared-key").walletCents())
                .isEqualTo(700);

        // 流水去重键按用户隔离，互不碰撞。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE external_biz_id = ?",
                Long.class, "RECHARGE:" + alice.getId() + ":shared-key")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE external_biz_id = ?",
                Long.class, "RECHARGE:" + bob.getId() + ":shared-key")).isEqualTo(1);
    }

    @Test
    void userCancelAndOrganiserCancelRaceRefundExactlyOnce() throws Exception {
        User buyer = newUser("USER", 5000);
        User organiser = newUser("ORGANISER", 0);
        Event a = newEvent(organiser.getId(), 300, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        Long bookingId = bookings.create(new CreateBookingRequest(a.getId(), 1), null).id();
        long balanceAfterBooking = walletOf(buyer.getId());

        Thread t1 = new Thread(() -> withLoginUnchecked(buyer.getId(), () -> bookings.cancel(bookingId)));
        Thread t2 = new Thread(() -> {
            BaseContext.setUserId(organiser.getId());
            BaseContext.setRole("ORGANISER");
            try {
                // 真实的主办方取消活动流程（同样通过 cancelConfirmed 领取退款资格）
                organiserEvents.cancel(a.getId(), new dev.kaiwen.eventpulse.dto.EventDtos.CancelEventRequest("竞争取消"));
            }
            catch (Exception ignored) {
                // 与用户取消竞争失败，或活动状态不再允许取消
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // 用户取消（带退款流水）成功执行；主办方取消的模拟领取与它竞争但只退一次
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND biz_type IN ('BOOKING_REFUND','EVENT_CANCEL_REFUND')",
                Long.class, buyer.getId())).isEqualTo(1);
        assertThat(walletOf(buyer.getId())).isEqualTo(balanceAfterBooking + 300);
    }

    // ------------------------------------------------------------------
    // 历史订单
    // ------------------------------------------------------------------

    @Test
    void historyListsEverythingWithPagingFiltersSearchAndPermission() {
        User buyer = newUser("USER", 0);
        User stranger = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event alpha = newEvent(organiser.getId(), 100, 100, EventStatus.PUBLISHED);
        Event beta = newEvent(organiser.getId(), 200, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        auth.recharge(buyer.getId(), new WalletRechargeRequest(10000), null);
        Long confirmed1 = bookings.create(new CreateBookingRequest(alpha.getId(), 1), null).id();
        Long confirmed2 = bookings.create(new CreateBookingRequest(beta.getId(), 2), null).id();
        bookings.cancel(confirmed2);

        // 取消的订单仍在历史里；默认倒序（id DESC 兜底稳定排序）
        var page0 = bookings.listMine(null, null, null, null, 0, 10);
        assertThat(page0.getTotal()).isEqualTo(2);
        assertThat(page0.getRecords()).extracting(BookingVo::id).containsExactly(confirmed2, confirmed1);

        var confirmedOnly = bookings.listMine("CONFIRMED", null, null, null, 0, 10);
        assertThat(confirmedOnly.getTotal()).isEqualTo(1);
        assertThat(confirmedOnly.getRecords().get(0).id()).isEqualTo(confirmed1);

        var cancelledOnly = bookings.listMine("CANCELLED", null, null, null, 0, 10);
        assertThat(cancelledOnly.getRecords().get(0).id()).isEqualTo(confirmed2);
        assertThat(cancelledOnly.getRecords().get(0).cancelledAt()).isNotNull();
        // 取消订单的退款信息
        assertThat(cancelledOnly.getRecords().get(0).refundCents()).isEqualTo(400);

        // 时间范围
        var window = bookings.listMine(null, Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                Instant.now().plus(1, ChronoUnit.HOURS).toString(), null, 0, 10);
        assertThat(window.getTotal()).isEqualTo(2);
        var emptyWindow = bookings.listMine(null, Instant.now().plus(1, ChronoUnit.DAYS).toString(), null, null, 0, 10);
        assertThat(emptyWindow.getTotal()).isZero();

        // 按订单号或活动名称搜索
        var byId = bookings.listMine(null, null, null, String.valueOf(confirmed1), 0, 10);
        assertThat(byId.getTotal()).isEqualTo(1);
        String title = page0.getRecords().get(0).eventTitle();
        var byTitle = bookings.listMine(null, null, null, title.substring(0, 8), 0, 10);
        assertThat(byTitle.getTotal()).isGreaterThanOrEqualTo(1);

        // 分页
        var page2 = bookings.listMine(null, null, null, null, 1, 1);
        assertThat(page2.getRecords()).hasSize(1);
        assertThat(page2.getRecords().get(0).id()).isEqualTo(confirmed1);

        // 详情带退款与票据统计；越权访问被拒绝
        BookingVo detail = bookings.get(confirmed2);
        assertThat(detail.refundCents()).isEqualTo(400);
        assertThat(detail.refundLedgerId()).isNotNull();
        assertThat(detail.cancelBlockReason()).isEqualTo("ALREADY_CANCELLED");
        BookingVo active = bookings.get(confirmed1);
        assertThat(active.cancellable()).isTrue();

        login(stranger.getId());
        assertThatThrownBy(() -> bookings.get(confirmed1)).isInstanceOf(BusinessException.class);
        assertThat(bookings.listMine(null, null, null, null, 0, 10).getTotal()).isZero();
    }

    // ------------------------------------------------------------------
    // 直接下单幂等 & 免费订单
    // ------------------------------------------------------------------

    @Test
    void directBookingWithIdempotencyKeyDoesNotDuplicate() {
        User buyer = newUser("USER", 0);
        User organiser = newUser("ORGANISER", 0);
        Event free = newEvent(organiser.getId(), 0, 100, EventStatus.PUBLISHED);
        login(buyer.getId());
        String key = "direct-" + SEQ.get();
        BookingVo first = bookings.create(new CreateBookingRequest(free.getId(), 2), key);
        BookingVo retry = bookings.create(new CreateBookingRequest(free.getId(), 2), key);

        assertThat(retry.id()).isEqualTo(first.id());
        // 免费订单：无资金流水，无扣款
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ?", Long.class, buyer.getId())).isZero();
        assertThat(walletOf(buyer.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Long.class, first.id())).isEqualTo(2);

        // 不带键的第二次下单是独立新订单
        BookingVo separate = bookings.create(new CreateBookingRequest(free.getId(), 1), null);
        assertThat(separate.id()).isNotEqualTo(first.id());
    }

    // ------------------------------------------------------------------

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> T withLogin(Long userId, Supplier<T> action) {
        BaseContext.setUserId(userId);
        BaseContext.setRole("USER");
        try {
            return action.get();
        }
        finally {
            BaseContext.clear();
        }
    }

    private void withLoginUnchecked(Long userId, Runnable action) {
        try {
            withLogin(userId, () -> {
                action.run();
                return null;
            });
        }
        catch (Exception ignored) {
            // 竞争失败方
        }
    }
}
