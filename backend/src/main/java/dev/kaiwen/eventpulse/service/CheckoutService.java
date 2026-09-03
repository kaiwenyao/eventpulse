package dev.kaiwen.eventpulse.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutItemRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.CartItem;
import dev.kaiwen.eventpulse.entity.Checkout;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.CartItemRepository;
import dev.kaiwen.eventpulse.repository.CheckoutRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;

/**
 * 批量结算：一次结算多个活动，每个活动生成独立 Booking（复用电子票 / 退款 / 通知）。
 *
 * 事务边界：库存、余额、订单、电子票、资金流水、Outbox 与购物车已购项移除
 * 在同一个 PostgreSQL 事务里完成；任意一项不可购买或总余额不足，
 * 整次回滚（购物车内容原样保留）并返回明确原因。HTTP 请求不等 Kafka。
 *
 * 并发与锁序：
 * - 活动行锁按 event_id 升序获取，与直接下单、用户取消、活动取消共用的
 *   「活动在前，钱包在后」顺序一致，多活动结算不引入新的锁顺序冲突；
 * - 钱包扣款按结算项顺序逐笔执行（每张订单一笔扣款流水），
 *   任一笔余额不足即抛 409，已执行的扣款与库存随事务一起回滚；
 * - 购物车行在验证前用 FOR UPDATE 锁定，与另一设备的并发修改串行化；
 *   移除按「减去本次购买数量」执行，另一设备后来新增的数量不会被误删。
 *
 * 幂等：服务端持久化的 (user_id, idempotency_key) 唯一键，在结算事务内登记。
 * 相同键 + 相同参数重试（即使购物车已清空）返回原订单；同键不同参数拒绝。
 * 并发重复点击由唯一索引串行化：后到事务等待前一个事务提交后命中既有行。
 */
@Service
public class CheckoutService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 120;

    private final CheckoutRepository checkouts;
    private final CartItemRepository cartItems;
    private final EventRepository events;
    private final BookingRepository bookings;
    private final TicketService ticketService;
    private final WalletService wallets;
    private final OutboxWriter outbox;
    private final PopularCache popularCache;

    public CheckoutService(
            CheckoutRepository checkouts,
            CartItemRepository cartItems,
            EventRepository events,
            BookingRepository bookings,
            TicketService ticketService,
            WalletService wallets,
            OutboxWriter outbox,
            PopularCache popularCache) {
        this.checkouts = checkouts;
        this.cartItems = cartItems;
        this.events = events;
        this.bookings = bookings;
        this.ticketService = ticketService;
        this.wallets = wallets;
        this.outbox = outbox;
        this.popularCache = popularCache;
    }

    /** 结算结果：checkoutId 仅在带幂等键的结算里非空；reused=true 表示幂等重放。 */
    public record Settlement(Long checkoutId, List<Booking> bookings, boolean reused) {

        public long totalPaidCents() {
            return bookings.stream().mapToLong(Booking::getPaidCents).sum();
        }
    }

    /** 直接下单（POST /api/bookings）：带幂等键时与购物车结算共用同一套幂等语义。 */
    @Transactional
    public Settlement settleDirect(Long userId, String idempotencyKey, Long eventId, int quantity) {
        // 幂等键先于任何业务读取 / 锁定登记：重试与并发重复请求在碰到
        // 购物车 / 活动 / 钱包之前就被引导到 replay 或等待，不会互相持锁。
        Long checkoutId = claimKey(userId, idempotencyKey,
                canonical(true, List.of(new long[] {eventId, quantity})));
        if (KEY_TAKEN.equals(checkoutId)) {
            return replayOfTakenKey(userId, idempotencyKey);
        }
        List<Pending> pending = List.of(Pending.direct(requireEvent(eventId), quantity));
        return runSettlement(userId, checkoutId, pending, false);
    }

    /**
     * 购物车批量结算：requested 只提供 itemId 与数量；活动、价格、库存、
     * 金额全部以数据库为准，不信任前端提交的任何金额或状态。
     */
    @Transactional
    public Settlement settleCart(Long userId, String idempotencyKey, List<CheckoutItemRequest> requested) {
        // 幂等键先登记：即使购物车已被清空（另一设备结算 / 清空），
        // 相同键的重试也在这里命中原订单，而不是报「购物车为空」。
        String canonical = canonical(true, requested.stream()
                .map(item -> new long[] {item.itemId(), item.quantity()})
                .toList());
        Long checkoutId = claimKey(userId, idempotencyKey, canonical);
        if (KEY_TAKEN.equals(checkoutId)) {
            return replayOfTakenKey(userId, idempotencyKey);
        }

        List<Long> itemIds = requested.stream().map(CheckoutItemRequest::itemId).distinct().toList();
        // FOR UPDATE 锁定本用户的购物车行：与另一设备的修改 / 移除串行化。
        List<CartItem> locked = cartItems.lockByIdsAndUser(itemIds, userId);
        if (locked.size() != itemIds.size()) {
            throw BusinessException.conflict("Cart was changed on another device, please refresh");
        }
        Map<Long, CartItem> byId = new LinkedHashMap<>();
        locked.forEach(item -> byId.put(item.getId(), item));
        List<Pending> pending = new ArrayList<>(requested.size());
        for (CheckoutItemRequest request : requested) {
            CartItem cartItem = byId.get(request.itemId());
            if (request.quantity() > cartItem.getQuantity()) {
                // 另一设备已把数量改小：拒绝而不是按旧数量结算或误删后来新增的数量。
                throw BusinessException.conflict("Cart item quantity was changed on another device, please refresh");
            }
            pending.add(Pending.fromCart(cartItem, requireEvent(cartItem.getEventId()), request.quantity()));
        }
        return runSettlement(userId, checkoutId, pending, true);
    }

    /**
     * 在结算事务内原子登记幂等键。
     *
     * @return 新登记的 checkoutId；{@link #KEY_TAKEN} 表示同键结算已存在
     *         （并发事务尚未提交，或已成功提交），调用方转去 replay / 等待。
     */
    private Long claimKey(Long userId, String idempotencyKey, String canonical) {
        String key = normalizeKey(idempotencyKey);
        if (key == null) {
            return null;
        }
        String requestHash = sha256(canonical);
        List<Long> inserted = checkouts.insertIfAbsent(userId, key, requestHash);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        Checkout existing = checkouts.findByUserIdAndIdempotencyKey(userId, key)
                .orElseThrow(() -> BusinessException.conflict("Checkout is being processed, retry shortly"));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw BusinessException.conflict("Idempotency key was already used with a different request");
        }
        return KEY_TAKEN;
    }

    private Settlement replayOfTakenKey(Long userId, String idempotencyKey) {
        Checkout existing = checkouts.findByUserIdAndIdempotencyKey(userId, normalizeKey(idempotencyKey))
                .orElseThrow(() -> BusinessException.conflict("Checkout is being processed, retry shortly"));
        return replay(userId, idempotencyKey, existing.getId());
    }

    /** 哨兵值：幂等键已被占用（参数一致的并发 / 重试）。 */
    private static final Long KEY_TAKEN = -1L;

    /** 幂等键命中已成功结算：读取原订单（购物车被清空也一样）。 */
    @Transactional(readOnly = true)
    public Settlement replay(Long userId, String idempotencyKey, Long checkoutId) {
        List<Booking> existing = bookings.findByCheckoutIdOrderByIdAsc(checkoutId);
        if (existing.isEmpty() || !existing.get(0).getUserId().equals(userId)) {
            throw BusinessException.notFound("Checkout not found");
        }
        return new Settlement(checkoutId, existing, true);
    }

    private record Pending(Long cartItemId, Event event, int quantity, int expectedUnitPriceCents) {

        static Pending direct(Event event, int quantity) {
            return new Pending(null, event, quantity, event.getPriceCents());
        }

        static Pending fromCart(CartItem cartItem, Event event, int quantity) {
            return new Pending(cartItem.getId(), event, quantity, cartItem.getPriceCents());
        }
    }

    /**
     * 结算核心。所有结算项按 event_id 升序处理（稳定锁序）。
     * checkoutId 为 null 表示无幂等键的直接下单（不写 checkouts、不关联购物车汇总事件）。
     */
    private Settlement runSettlement(Long userId, Long checkoutId, List<Pending> pending, boolean cartMode) {

        // 稳定锁序：全部结算项按 event_id 升序处理。
        List<Pending> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator.comparing((Pending item) -> item.event().getId()));

        long totalAmount = 0;
        List<Booking> created = new ArrayList<>(ordered.size());
        for (Pending item : ordered) {
            Event event = item.event();
            int quantity = item.quantity();

            // 1) 重新校验可订性与数量。
            String reason = EventService.unbookableReason(event, Instant.now());
            if (reason != null) {
                throw BusinessException.conflict(reason);
            }
            int maxQty = CartService.maxPerBooking(event);
            if (quantity > maxQty) {
                throw new BusinessException("Maximum " + maxQty + " tickets per booking");
            }
            // 2) 价格快照比对（购物车项）：价格变化必须由用户重新确认。
            if (item.cartItemId() != null && item.expectedUnitPriceCents() != event.getPriceCents()) {
                throw BusinessException.conflict("Price has changed, please review and confirm the new price");
            }
            long lineAmount = Math.multiplyExact((long) event.getPriceCents(), quantity);

            // 3) 占库存：条件更新，0 行 = 售罄或状态已变 → 整次回滚。
            if (events.incrementSold(event.getId(), quantity) == 0) {
                Event latest = requireEvent(event.getId());
                String latestReason = EventService.unbookableReason(latest, Instant.now());
                throw BusinessException.conflict(latestReason == null ? "Sold out" : latestReason);
            }

            // 4) 订单（价格快照）→ 扣款流水 → 电子票 → 订单事件，全部同事务。
            Booking booking = new Booking();
            booking.setUserId(userId);
            booking.setEventId(event.getId());
            booking.setQuantity(quantity);
            booking.setPaidCents(lineAmount);
            booking.setUnitPriceCents((long) event.getPriceCents());
            booking.setCheckoutId(checkoutId);
            booking.setStatus("CONFIRMED");
            booking.setCreatedAt(Instant.now());
            bookings.save(booking);

            if (lineAmount > 0) {
                // 每张订单一笔扣款流水；dedup = PAY:{bookingId}，同一订单最多扣一次。
                wallets.debit(userId, lineAmount, WalletLedger.TYPE_BOOKING_PAYMENT,
                        "PAY:" + booking.getId(), booking.getId(), checkoutId,
                        "Payment for booking #" + booking.getId() + " (" + event.getTitle() + " x" + quantity + ")");
            }

            ticketService.issue(booking.getId(), event.getId(), quantity);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "BOOKING_CREATED");
            payload.put("userId", userId);
            payload.put("eventId", event.getId());
            payload.put("bookingId", booking.getId());
            payload.put("quantity", quantity);
            if (checkoutId != null) {
                payload.put("checkoutId", checkoutId);
            }
            payload.put("title", "Booking confirmed");
            payload.put("message", "You booked " + quantity + " ticket(s) for \"" + event.getTitle() + "\"");
            outbox.write(KafkaTopics.BOOKING_EVENTS, "BOOKING_CREATED", "booking:" + booking.getId(),
                    "BOOKING_CREATED:" + booking.getId(), payload);

            created.add(booking);
            totalAmount += lineAmount;
        }

        // 5) 只移除本次实际购买的数量：另一设备后来新增的数量留在购物车里。
        if (cartMode) {
            Instant now = Instant.now();
            for (int i = 0; i < created.size(); i++) {
                Long cartItemId = ordered.get(i).cartItemId();
                int purchased = created.get(i).getQuantity();
                // 先删「恰好买完」的行；否则部分扣减（剩余数量始终 >= 1，
                // 不会触发 quantity CHECK）；两步都影响 0 行说明被并发改小。
                int removed = cartItems.deleteIfPurchased(cartItemId, purchased);
                if (removed == 0 && cartItems.decrementQuantity(cartItemId, purchased, now) == 0) {
                    throw BusinessException.conflict("Cart was changed on another device, please refresh");
                }
            }
            if (checkoutId != null) {
                writeCheckoutCompletedEvent(userId, checkoutId, created, totalAmount);
            }
        }

        popularCache.evict();
        return new Settlement(checkoutId, created, false);
    }

    private void writeCheckoutCompletedEvent(Long userId, Long checkoutId, List<Booking> created, long totalAmount) {
        int totalQuantity = created.stream().mapToInt(Booking::getQuantity).sum();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("eventType", "CART_CHECKOUT_COMPLETED");
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("userId", userId);
        payload.put("checkoutId", checkoutId);
        payload.put("itemCount", created.size());
        payload.put("totalQuantity", totalQuantity);
        payload.put("totalAmountCents", totalAmount);
        payload.put("dedupKey", "CART_CHECKOUT:" + checkoutId);
        outbox.write(KafkaTopics.CART_EVENTS, "CART_CHECKOUT_COMPLETED", "cart:" + userId,
                "CART_CHECKOUT:" + checkoutId, payload);
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException("Idempotency key is too long");
        }
        return key;
    }

    /**
     * 请求参数指纹：排序后的 (itemId / eventId, quantity) 对，直接来自请求参数，
     * 与数据库状态无关——同键同参数的重试（即使购物车已清空）哈希一致。
     * 同一幂等键配不同参数会被拒绝，不会把别的结算结果当成本次响应。
     */
    static String canonical(boolean cartMode, List<long[]> items) {
        List<long[]> sorted = new ArrayList<>(items);
        sorted.sort(java.util.Arrays::compare);
        StringBuilder canonical = new StringBuilder(cartMode ? "cart|" : "direct|");
        for (long[] item : sorted) {
            canonical.append(item[0]).append('|').append(item[1]).append('\n');
        }
        return canonical.toString();
    }

    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Event requireEvent(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> BusinessException.notFound("Event not found"));
    }
}
