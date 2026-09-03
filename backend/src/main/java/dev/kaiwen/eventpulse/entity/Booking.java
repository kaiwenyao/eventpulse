package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private int quantity;

    /** Amount actually paid from the wallet, in cents. Kept as a price snapshot for refunds. */
    @Column(name = "paid_cents", nullable = false)
    private long paidCents;

    /** 下单时的单价快照（分）。老订单由迁移从 paid_cents 推导，展示不再依赖当前活动价格。 */
    @Column(name = "unit_price_cents")
    private Long unitPriceCents;

    /** 同一次购物车结算的关联标识；直接下单（未带幂等键）时为空。 */
    @Column(name = "checkout_id")
    private Long checkoutId;

    /** CONFIRMED 已确认；CANCELLED 已取消。创建后立刻确认，并往 Kafka 发一条消息。 */
    @Column(nullable = false)
    private String status;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "organiser_note")
    private String organiserNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getPaidCents() {
        return paidCents;
    }

    public void setPaidCents(long paidCents) {
        this.paidCents = paidCents;
    }

    public Long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(Long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
    }

    public Long getCheckoutId() {
        return checkoutId;
    }

    public void setCheckoutId(Long checkoutId) {
        this.checkoutId = checkoutId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getOrganiserNote() {
        return organiserNote;
    }

    public void setOrganiserNote(String organiserNote) {
        this.organiserNote = organiserNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
