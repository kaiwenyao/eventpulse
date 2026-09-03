package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.RelatedBookingVo;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.WalletLedgerRepository;

@Service
public class BookingService {

    /** 历史订单可见的全部真实订单状态（没有「待支付」等无流程支撑的状态）。 */
    private static final Set<String> BOOKING_STATUSES = Set.of("CONFIRMED", "CANCELLED");

    private static final int MAX_PAGE_SIZE = 100;

    private final BookingRepository bookings;
    private final EventService eventService;
    private final EventRepository events;
    private final TicketService ticketService;
    private final TicketRepository tickets;
    private final CheckoutService checkoutService;
    private final WalletService wallets;
    private final WalletLedgerRepository walletLedgers;
    private final OutboxWriter outbox;
    private final PopularCache popularCache;

    public BookingService(
            BookingRepository bookings,
            EventService eventService,
            EventRepository events,
            TicketService ticketService,
            TicketRepository tickets,
            CheckoutService checkoutService,
            WalletService wallets,
            WalletLedgerRepository walletLedgers,
            OutboxWriter outbox,
            PopularCache popularCache) {
        this.bookings = bookings;
        this.eventService = eventService;
        this.events = events;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.checkoutService = checkoutService;
        this.wallets = wallets;
        this.walletLedgers = walletLedgers;
        this.outbox = outbox;
        this.popularCache = popularCache;
    }

    /**
     * 直接下单。带幂等键（Idempotency-Key 头）时走与购物车结算相同的
     * 服务端持久化幂等：重试 / 并发重复点击不会重复下单或重复扣款，
     * 已成功的重试返回原订单；不带键保持原有行为。
     */
    @Transactional
    public BookingVo create(CreateBookingRequest request, String idempotencyKey) {
        Long userId = requireLogin();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Booking booking = checkoutService
                    .settleDirect(userId, idempotencyKey, request.eventId(), request.quantity())
                    .bookings()
                    .get(0);
            return hydrate(List.of(booking), false).get(0);
        }

        Event event = eventService.require(request.eventId());
        String reason = EventService.unbookableReason(event, Instant.now());
        if (reason != null) {
            throw "Sold out".equals(reason) ? BusinessException.conflict(reason) : new BusinessException(reason);
        }
        int maxQty = event.getMaxQuantityPerBooking() <= 0 ? 10 : event.getMaxQuantityPerBooking();
        if (request.quantity() > maxQty) {
            throw new BusinessException("Maximum " + maxQty + " tickets per booking");
        }
        long paidCents = Math.multiplyExact((long) event.getPriceCents(), request.quantity());
        // Keep the activity-before-wallet order used by both cancellation flows to avoid deadlocks.
        int updated = events.incrementSold(event.getId(), request.quantity());
        if (updated == 0) {
            Event latest = eventService.require(request.eventId());
            String latestReason = EventService.unbookableReason(latest, Instant.now());
            throw BusinessException.conflict(latestReason == null ? "Sold out" : latestReason);
        }

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setEventId(event.getId());
        booking.setQuantity(request.quantity());
        booking.setPaidCents(paidCents);
        booking.setUnitPriceCents((long) event.getPriceCents());
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(Instant.now());
        bookings.save(booking);

        if (paidCents > 0) {
            // 直接预订的扣款流水：dedup = PAY:{bookingId}，重试不会重复扣款。
            wallets.debit(userId, paidCents, WalletLedger.TYPE_BOOKING_PAYMENT,
                    "PAY:" + booking.getId(), booking.getId(), null,
                    "Payment for booking #" + booking.getId() + " (" + event.getTitle() + " x" + request.quantity() + ")");
        }
        ticketService.issue(booking.getId(), event.getId(), request.quantity());
        outbox.write(KafkaTopics.BOOKING_EVENTS, "BOOKING_CREATED", "booking:" + booking.getId(),
                "BOOKING_CREATED:" + booking.getId(),
                Map.of(
                        "type", "BOOKING_CREATED",
                        "userId", userId,
                        "eventId", event.getId(),
                        "bookingId", booking.getId(),
                        "quantity", request.quantity(),
                        "title", "Booking confirmed",
                        "message", "You booked " + request.quantity() + " ticket(s) for \"" + event.getTitle() + "\""));
        popularCache.evict();
        return hydrate(List.of(booking), false).get(0);
    }

    /**
     * 历史订单列表（服务端分页）：默认展示全部真实状态（CONFIRMED / CANCELLED），
     * 不因活动结束、归档或取消而隐藏。支持状态筛选、时间范围、订单号 / 活动名搜索，
     * 排序 created_at DESC + id DESC 稳定次级排序。分页、筛选在数据库完成。
     */
    @Transactional(readOnly = true)
    public PageResult<BookingVo> listMine(String status, String from, String to, String q, Integer page, Integer size) {
        Long userId = requireLogin();
        String normalizedStatus = normalizeStatus(status);
        Instant fromAt = parseInstant(from, "from");
        Instant toAt = parseInstant(to, "to");
        String search = normalizeSearch(q);
        long qNumeric = search != null && search.chars().allMatch(Character::isDigit) ? Long.parseLong(search) : -1;
        String qLike = search == null ? null : "%" + search + "%";

        int safeSize = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        int safePage = page == null || page < 0 ? 0 : page;
        long total = bookings.searchCount(userId, normalizedStatus, fromAt, toAt, search, qNumeric, qLike);
        List<Booking> rows = bookings.searchPage(userId, normalizedStatus, fromAt, toAt, search, qNumeric, qLike,
                safeSize, safePage * safeSize);
        return new PageResult<>(total, hydrate(rows, false));
    }

    /** 订单详情：含退款流水、同次结算关联订单与不可取消原因。 */
    @Transactional(readOnly = true)
    public BookingVo get(Long id) {
        return hydrate(List.of(requireOwn(id)), true).get(0);
    }

    public List<TicketService.TicketView> tickets(Long id) {
        requireOwn(id);
        return ticketService.forBooking(id).stream()
                .map(ticket -> ticketService.toView(ticket, true))
                .toList();
    }

    @Transactional
    public BookingVo cancel(Long id) {
        Booking booking = requireOwn(id);
        Event event = eventService.require(booking.getEventId());
        // Keep the activity -> ticket -> booking -> wallet order used when an organiser cancels an event.
        if (events.decrementSoldForCustomerCancellation(event.getId(), booking.getQuantity()) == 0) {
            throw BusinessException.conflict("Event has started or cannot be cancelled in its current state");
        }
        List<dev.kaiwen.eventpulse.entity.Ticket> lockedTickets = ticketService.lockForBooking(booking.getId());
        if (lockedTickets.stream().anyMatch(ticket -> TicketStatus.CHECKED_IN.equals(ticket.getStatus()))) {
            throw BusinessException.conflict("A ticket has already been checked in, refund is not allowed");
        }
        // 作废票必须排在 cancelConfirmed 之前：那条 @Modifying(clearAutomatically = true)
        // 会清空持久化上下文，之后 lockedTickets 就成了游离实体，改了也不会落库——
        // 结果是订单已退款、票却仍是 VALID，可以照常核销入场。放在前面，
        // cancelConfirmed 的 flushAutomatically 会先把票的改动刷进数据库。
        // 若紧接着的 cancelConfirmed 落空而抛错，整个事务回滚，这里的改动一并撤销。
        ticketService.cancelLocked(lockedTickets);
        if (bookings.cancelConfirmed(booking.getId()) == 0) {
            throw new BusinessException("Booking already cancelled");
        }
        booking.setStatus("CANCELLED");
        booking.setCancelledAt(Instant.now());
        if (booking.getPaidCents() > 0) {
            // 退款流水：dedup = REFUND:{bookingId}。用户取消与主办方取消活动竞争时，
            // 慢的一方要么抢不到 cancelConfirmed（0 行），要么撞流水唯一约束，都不会重复退款。
            wallets.credit(booking.getUserId(), booking.getPaidCents(), WalletLedger.TYPE_BOOKING_REFUND,
                    "REFUND:" + booking.getId(), booking.getId(), booking.getCheckoutId(),
                    "Refund for cancelled booking #" + booking.getId() + " (" + event.getTitle() + ")");
        }
        outbox.write(KafkaTopics.BOOKING_EVENTS, "BOOKING_CANCELLED", "booking:" + booking.getId(),
                "BOOKING_CANCELLED:" + booking.getId(),
                Map.of(
                        "type", "BOOKING_CANCELLED",
                        "userId", booking.getUserId(),
                        "eventId", event.getId(),
                        "bookingId", booking.getId(),
                        "quantity", booking.getQuantity(),
                        "title", "Booking cancelled",
                        "message", "You cancelled your booking for \"" + event.getTitle() + "\""));
        popularCache.evict();
        return hydrate(List.of(booking), true).get(0);
    }

    private Booking requireOwn(Long id) {
        Long userId = requireLogin();
        Booking booking = bookings.findById(id).orElseThrow(() -> BusinessException.notFound("Booking not found"));
        if (!booking.getUserId().equals(userId)) {
            throw BusinessException.forbidden("You can only view your own bookings");
        }
        return booking;
    }

    private static Long requireLogin() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        return userId;
    }

    public BookingVo toPublic(Booking booking) {
        return hydrate(List.of(booking), false).get(0);
    }

    /** 批量装配订单视图（购物车结算结果用）：一次查齐活动 / 票据计数 / 退款流水。 */
    public List<BookingVo> toVoList(List<Booking> rows) {
        return hydrate(rows, false);
    }

    // ------------------------------------------------------------------
    // 视图装配：批量查活动 / 票据计数 / 退款流水，避免逐单查询（N+1）。
    // 历史金额全部来自订单快照，不用当前活动价格重算。
    // ------------------------------------------------------------------

    private List<BookingVo> hydrate(List<Booking> rows, boolean includeRelated) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> eventIds = new HashSet<>();
        rows.forEach(booking -> eventIds.add(booking.getEventId()));
        Map<Long, Event> eventById = new HashMap<>();
        events.findAllById(eventIds).forEach(event -> eventById.put(event.getId(), event));

        List<Long> bookingIds = rows.stream().map(Booking::getId).toList();
        Map<Long, long[]> ticketCounts = new HashMap<>();
        for (Object[] row : tickets.countGroupedByBookingIds(bookingIds)) {
            long bookingId = ((Number) row[0]).longValue();
            String ticketStatus = String.valueOf(row[1]);
            long count = ((Number) row[2]).longValue();
            long[] counts = ticketCounts.computeIfAbsent(bookingId, key -> new long[2]);
            if (TicketStatus.CHECKED_IN.equals(ticketStatus)) {
                counts[0] = count;
            }
            else if (TicketStatus.VALID.equals(ticketStatus)) {
                counts[1] = count;
            }
        }

        Map<Long, WalletLedger> refunds = new HashMap<>();
        walletLedgers.findByBookingIdInAndBizTypeIn(bookingIds,
                List.of(WalletLedger.TYPE_BOOKING_REFUND, WalletLedger.TYPE_EVENT_CANCEL_REFUND))
                .forEach(ledger -> refunds.put(ledger.getBookingId(), ledger));

        Map<Long, List<Booking>> related = new HashMap<>();
        if (includeRelated) {
            Set<Long> checkoutIds = new HashSet<>();
            rows.forEach(booking -> {
                if (booking.getCheckoutId() != null) {
                    checkoutIds.add(booking.getCheckoutId());
                }
            });
            for (Long checkoutId : checkoutIds) {
                related.put(checkoutId, bookings.findByCheckoutIdOrderByIdAsc(checkoutId));
            }
        }

        Instant now = Instant.now();
        List<BookingVo> vos = new ArrayList<>(rows.size());
        for (Booking booking : rows) {
            Event event = eventById.get(booking.getEventId());
            long[] counts = ticketCounts.getOrDefault(booking.getId(), new long[2]);
            WalletLedger refund = refunds.get(booking.getId());

            boolean confirmed = "CONFIRMED".equals(booking.getStatus());
            boolean eventBookable = event != null
                    && EventStatus.PUBLISHED.equals(event.getStatus())
                    && now.isBefore(event.getStartsAt());
            boolean cancellable = confirmed && eventBookable && counts[0] == 0;
            String cancelBlockReason = null;
            if (!cancellable) {
                if (!confirmed) {
                    cancelBlockReason = "ALREADY_CANCELLED";
                }
                else if (counts[0] > 0) {
                    cancelBlockReason = "TICKET_CHECKED_IN";
                }
                else if (event == null) {
                    cancelBlockReason = "EVENT_MISSING";
                }
                else if (!EventStatus.PUBLISHED.equals(event.getStatus())) {
                    cancelBlockReason = EventStatus.CANCELLED.equals(event.getStatus())
                            ? "EVENT_CANCELLED" : "EVENT_NOT_PUBLISHED";
                }
                else {
                    cancelBlockReason = "EVENT_STARTED";
                }
            }

            List<RelatedBookingVo> relatedVos = null;
            if (includeRelated && booking.getCheckoutId() != null) {
                relatedVos = related.getOrDefault(booking.getCheckoutId(), List.of()).stream()
                        .map(other -> new RelatedBookingVo(
                                other.getId(),
                                other.getEventId(),
                                eventById.get(other.getEventId()) == null ? null
                                        : eventById.get(other.getEventId()).getTitle(),
                                other.getQuantity(),
                                other.getPaidCents()))
                        .toList();
            }

            vos.add(new BookingVo(
                    booking.getId(),
                    booking.getEventId(),
                    event == null ? null : event.getTitle(),
                    event == null ? null : event.getStatus(),
                    event == null ? null : event.getStartsAt(),
                    booking.getQuantity(),
                    booking.getUnitPriceCents(),
                    booking.getPaidCents(),
                    booking.getStatus(),
                    booking.getCreatedAt(),
                    booking.getCancelledAt(),
                    booking.getOrganiserNote(),
                    counts[0],
                    counts[1],
                    refund == null ? 0 : refund.getAmountCents(),
                    refund == null ? null : refund.getId(),
                    booking.getCheckoutId(),
                    cancellable,
                    cancelBlockReason,
                    relatedVos));
        }
        return vos;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        String upper = status.trim().toUpperCase();
        if (!BOOKING_STATUSES.contains(upper)) {
            throw new BusinessException("Unknown booking status: " + status);
        }
        return upper;
    }

    private static String normalizeSearch(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException e) {
            throw new BusinessException(field + " must be an ISO-8601 instant");
        }
    }
}
