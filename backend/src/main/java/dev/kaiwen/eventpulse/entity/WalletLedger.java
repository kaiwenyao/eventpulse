package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 钱包流水（只追加）。每一次余额变动（充值、下单扣款、取消退款、期初迁移）
 * 都在同一数据库事务里写入一行，与 users.wallet_cents 的原子 UPDATE 绑定；
 * 不通过 Kafka 或异步任务补写。既有业务流水不会被修改或删除。
 */
@Entity
@Table(name = "wallet_ledger")
public class WalletLedger {

    /** RECHARGE 充值；BOOKING_PAYMENT 下单扣款；BOOKING_REFUND 用户取消退款；
     *  EVENT_CANCEL_REFUND 主办方取消活动退款；OPENING_BALANCE 流水体系启用时的期初余额。 */
    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_BOOKING_PAYMENT = "BOOKING_PAYMENT";
    public static final String TYPE_BOOKING_REFUND = "BOOKING_REFUND";
    public static final String TYPE_EVENT_CANCEL_REFUND = "EVENT_CANCEL_REFUND";
    public static final String TYPE_OPENING_BALANCE = "OPENING_BALANCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "biz_type", nullable = false)
    private String bizType;

    /** 带正负号的变动金额（分）：收入为正、支出为负。 */
    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "balance_before_cents", nullable = false)
    private long balanceBeforeCents;

    @Column(name = "balance_after_cents", nullable = false)
    private long balanceAfterCents;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "checkout_id")
    private Long checkoutId;

    /** 业务去重标识：同一业务操作最多一条流水（如 REFUND:123 最多一次）。 */
    @Column(name = "external_biz_id", nullable = false, unique = true)
    private String externalBizId;

    @Column
    private String description;

    /** 账户内顺序：与余额更新在同一条原子 UPDATE 里递增的 users.ledger_seq。 */
    @Column(name = "seq_no", nullable = false)
    private long seqNo;

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

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public long getBalanceBeforeCents() {
        return balanceBeforeCents;
    }

    public void setBalanceBeforeCents(long balanceBeforeCents) {
        this.balanceBeforeCents = balanceBeforeCents;
    }

    public long getBalanceAfterCents() {
        return balanceAfterCents;
    }

    public void setBalanceAfterCents(long balanceAfterCents) {
        this.balanceAfterCents = balanceAfterCents;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getCheckoutId() {
        return checkoutId;
    }

    public void setCheckoutId(Long checkoutId) {
        this.checkoutId = checkoutId;
    }

    public String getExternalBizId() {
        return externalBizId;
    }

    public void setExternalBizId(String externalBizId) {
        this.externalBizId = externalBizId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(long seqNo) {
        this.seqNo = seqNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
