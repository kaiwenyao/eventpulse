package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.dto.CartDtos.CartItemVo;
import dev.kaiwen.eventpulse.dto.CartDtos.CartVo;
import dev.kaiwen.eventpulse.entity.CartItem;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.CartItemRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;

/**
 * 购物车：数据库持久化（换设备 / 重新登录仍在），身份取自服务端登录上下文。
 * 加购不扣余额、不占库存；金额只是展示，结算由 CheckoutService 重新校验。
 *
 * 上限：每个用户最多 {@value #MAX_ITEMS_PER_USER} 个活动项；
 * 单项数量 1..min(活动限购, 当前余票, 99)（数据库另有 CHECK 约束兜底）。
 * 详情页对外展示的「最多 N 张」= min(活动限购, 余票)，写入校验必须同口径，
 * 否则余票少于限购时会出现「详情页说最多 4 张、购物车却能加到 10 张」。
 * 余票是时点值（加购不占库存，别人可能先买走），所以只拦「加数量」，
 * 「减数量」永远放行，仍超余票的行靠 LOW_STOCK 提示 + 结算拦截兜底。
 * 同一用户同一活动由数据库唯一约束合并为一行。
 * 列表顺序：按加购时间倒序（created_at DESC，id DESC 兜底）。改数量 / 勾选 /
 * 价格确认都会刷新 updated_at，但不能作为排序键，否则刚操作过的物品会跳到最前。
 */
@Service
public class CartService {

    static final int MAX_ITEMS_PER_USER = 20;
    static final int HARD_MAX_QUANTITY = 99;

    private final CartItemRepository cartItems;
    private final EventRepository events;
    private final EventService eventService;
    private final OutboxWriter outbox;

    public CartService(CartItemRepository cartItems, EventRepository events, EventService eventService,
            OutboxWriter outbox) {
        this.cartItems = cartItems;
        this.events = events;
        this.eventService = eventService;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public CartVo view() {
        Long userId = requireLogin();
        List<CartItem> items = cartItems.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        return toVo(userId, items);
    }

    /** 加购：同一活动合并为一行；合并后的数量受活动限购与当前余票共同约束。 */
    @Transactional
    public CartVo add(Long eventId, int quantity) {
        Long userId = requireLogin();
        if (quantity < 1 || quantity > HARD_MAX_QUANTITY) {
            throw new BusinessException("Quantity must be between 1 and " + HARD_MAX_QUANTITY);
        }
        Event event = requireEvent(eventId);
        // 只允许在可订状态下加购；之后的状态变化在结算时再重新校验。
        String reason = EventService.unbookableReason(event, Instant.now());
        if (reason != null) {
            throw BusinessException.conflict(reason);
        }
        int maxQty = maxPerBooking(event);
        if (quantity > maxQty) {
            throw new BusinessException("Maximum " + maxQty + " tickets per booking");
        }
        if (quantity > event.remaining()) {
            throw new BusinessException("Only " + event.remaining() + " tickets left");
        }
        CartItem item = cartItems.findByUserIdAndEventId(userId, eventId).orElse(null);
        if (item == null) {
            if (cartItems.countByUserId(userId) >= MAX_ITEMS_PER_USER) {
                throw BusinessException.conflict("Cart is full: at most " + MAX_ITEMS_PER_USER + " events per cart");
            }
            item = new CartItem();
            item.setUserId(userId);
            item.setEventId(eventId);
            item.setQuantity(quantity);
            item.setSelected(true);
            item.setPriceCents(event.getPriceCents());
            item.setVersion(1);
            item.setCreatedAt(Instant.now());
            item.setUpdatedAt(Instant.now());
            cartItems.save(item);
            // 统计口径：只有「新加入购物车」触发的 CART_ITEM_ADDED 计入加购统计，
            // 数量在已有行上的合并只发 UPDATED，不会重复计数。
            writeItemEvent("CART_ITEM_ADDED", item, quantity);
        }
        else {
            int merged = item.getQuantity() + quantity;
            if (merged > maxQty) {
                throw BusinessException.conflict("Maximum " + maxQty + " tickets per booking for this event");
            }
            if (merged > event.remaining()) {
                throw BusinessException.conflict("Only " + event.remaining() + " tickets left");
            }
            item.setQuantity(merged);
            item.setUpdatedAt(Instant.now());
            item.setVersion(item.getVersion() + 1);
            cartItems.save(item);
            writeItemEvent("CART_ITEM_UPDATED", item, quantity);
        }
        return view();
    }

    @Transactional
    public CartVo update(Long itemId, Integer quantity, Boolean selected) {
        Long userId = requireLogin();
        CartItem item = cartItems.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> BusinessException.notFound("Cart item not found"));
        if (quantity == null && selected == null) {
            throw new BusinessException("Nothing to update");
        }
        if (quantity != null) {
            Event event = requireEvent(item.getEventId());
            int maxQty = Math.min(maxPerBooking(event), HARD_MAX_QUANTITY);
            if (quantity < 1 || quantity > maxQty) {
                throw new BusinessException("Quantity must be between 1 and " + maxQty);
            }
            // 余票是时点值：加购后可能变少，所以只拦「往上加」，往下减永远放行
            //（减完仍超余票的行会挂 LOW_STOCK 提示，结算时再拦）。
            if (quantity > item.getQuantity() && quantity > event.remaining()) {
                throw new BusinessException("Only " + event.remaining() + " tickets left");
            }
            item.setQuantity(quantity);
        }
        if (selected != null) {
            item.setSelected(selected);
        }
        item.setUpdatedAt(Instant.now());
        item.setVersion(item.getVersion() + 1);
        cartItems.save(item);
        writeItemEvent("CART_ITEM_UPDATED", item, item.getQuantity());
        return view();
    }

    @Transactional
    public CartVo remove(Long itemId) {
        Long userId = requireLogin();
        CartItem item = cartItems.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> BusinessException.notFound("Cart item not found"));
        writeItemEvent("CART_ITEM_REMOVED", item, 0);
        cartItems.delete(item);
        return view();
    }

    @Transactional
    public CartVo clear() {
        Long userId = requireLogin();
        List<CartItem> items = cartItems.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        items.forEach(item -> writeItemEvent("CART_ITEM_REMOVED", item, 0));
        cartItems.deleteAll(items);
        return view();
    }

    /**
     * 价格变化后的用户确认：把价格快照刷新为当前价。
     * 只有用户显式调用（前端在价格确认弹窗里点「按新价格结算」）才会执行；
     * 结算绝不静默按快照以外的价格扣款。
     */
    @Transactional
    public CartVo refreshPrices() {
        Long userId = requireLogin();
        List<CartItem> items = cartItems.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        List<Event> related = events.findAllById(items.stream().map(CartItem::getEventId).toList());
        Map<Long, Event> byId = new HashMap<>();
        related.forEach(event -> byId.put(event.getId(), event));
        for (CartItem item : items) {
            Event event = byId.get(item.getEventId());
            if (event != null && event.getPriceCents() != item.getPriceCents()) {
                item.setPriceCents(event.getPriceCents());
                item.setUpdatedAt(Instant.now());
                item.setVersion(item.getVersion() + 1);
                cartItems.save(item);
                writeItemEvent("CART_ITEM_UPDATED", item, 0);
            }
        }
        return view();
    }

    private CartVo toVo(Long userId, List<CartItem> items) {
        if (items.isEmpty()) {
            return new CartVo(List.of(), 0, false);
        }
        List<Event> related = events.findAllById(items.stream().map(CartItem::getEventId).toList());
        Map<Long, Event> byId = new HashMap<>();
        related.forEach(event -> byId.put(event.getId(), event));
        Instant now = Instant.now();
        List<CartItemVo> rows = new ArrayList<>(items.size());
        long selectedTotal = 0;
        boolean hasIssues = false;
        for (CartItem item : items) {
            Event event = byId.get(item.getEventId());
            List<String> issues = issuesOf(item, event, now);
            hasIssues = hasIssues || !issues.isEmpty();
            long lineTotal = (long) item.getPriceCents() * item.getQuantity();
            if (item.isSelected()) {
                selectedTotal += lineTotal;
            }
            rows.add(new CartItemVo(
                    item.getId(),
                    item.getEventId(),
                    event == null ? null : event.getTitle(),
                    event == null ? null : event.getStatus(),
                    event == null ? null : event.getStartsAt(),
                    item.getQuantity(),
                    item.getPriceCents(),
                    event == null ? item.getPriceCents() : event.getPriceCents(),
                    lineTotal,
                    item.isSelected(),
                    event == null ? 0 : maxPerBooking(event),
                    event == null ? 0 : Math.max(0, event.remaining()),
                    issues));
        }
        return new CartVo(rows, selectedTotal, hasIssues);
    }

    /** 失效原因（机器键）：活动状态 / 售卖窗口 / 库存 / 价格变化 / 数量超限分开展示。 */
    private static List<String> issuesOf(CartItem item, Event event, Instant now) {
        List<String> issues = new ArrayList<>();
        if (event == null) {
            issues.add("EVENT_NOT_FOUND");
            return issues;
        }
        String key = EventService.unbookableKey(event, now);
        if (key != null) {
            issues.add(key);
        }
        if (event.getPriceCents() != item.getPriceCents()) {
            issues.add("PRICE_CHANGED");
        }
        int maxQty = maxPerBooking(event);
        if (item.getQuantity() > maxQty) {
            issues.add("OVER_LIMIT");
        }
        if (key == null && event.remaining() < item.getQuantity()) {
            issues.add("LOW_STOCK");
        }
        return issues;
    }

    /**
     * cart-events 只公告「已提交的变更」，不是执行命令。payload 携带
     * messageId / schemaVersion / 版本号；dedupKey = CART_ITEM:{id}:v{version}。
     * 消费端按 (groupId, dedupKey) 去重，并按 version 丢弃乱序旧事件。
     */
    private void writeItemEvent(String eventType, CartItem item, int deltaQuantity) {
        long version = item.getVersion();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("userId", item.getUserId());
        payload.put("itemId", item.getId());
        payload.put("eventId", item.getEventId());
        payload.put("quantity", item.getQuantity());
        payload.put("deltaQuantity", deltaQuantity);
        payload.put("version", version);
        payload.put("dedupKey", "CART_ITEM:" + item.getId() + ":v" + version);
        outbox.write(KafkaTopics.CART_EVENTS, eventType, "cart:" + item.getUserId(),
                "CART_ITEM:" + item.getId() + ":v" + version, payload);
    }

    static int maxPerBooking(Event event) {
        int max = event.getMaxQuantityPerBooking();
        return max <= 0 ? 10 : Math.min(max, HARD_MAX_QUANTITY);
    }

    private Event requireEvent(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> BusinessException.notFound("Event not found"));
    }

    private static Long requireLogin() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        return userId;
    }
}
