package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.dto.WalletDtos.LedgerVo;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.WalletLedgerRepository;

/**
 * 钱包记账的唯一入口：余额变动与流水写入永远在调用方的同一个数据库事务里完成，
 * 并在同事务写入 wallet-events 的 Outbox 消息。不通过 Kafka / 异步任务补写资金流水。
 *
 * 并发安全的核心是一条带条件的原子 UPDATE ... RETURNING：
 * 「余额足够才扣 / 未到上限才加」由数据库在最新已提交版本上判断，
 * RETURNING 得到的就是受行锁保护的真实新余额，因此
 * 「变动前余额 + 变动金额 = 变动后余额」与 users.ledger_seq 的递增严格一致。
 */
@Service
public class WalletService {

    /** 与 AuthService 演示充值一致的余额上限（€100,000,000，分）。 */
    public static final long MAX_WALLET_CENTS = 10_000_000_000L;

    private final JdbcTemplate jdbc;
    private final WalletLedgerRepository ledgers;
    private final OutboxWriter outbox;

    public WalletService(JdbcTemplate jdbc, WalletLedgerRepository ledgers, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.ledgers = ledgers;
        this.outbox = outbox;
    }

    /**
     * 一次余额变动的结果：流水 id、账户内序号与变动前后余额。
     * balanceBefore / balanceAfter 来自数据库真实状态，不来自请求。
     */
    public record Mutation(long ledgerId, long amountCents, long balanceBefore, long balanceAfter, long seqNo) {
    }

    /**
     * 扣款：amountCents > 0，从余额中扣。余额不足抛 409，调用方事务整体回滚。
     * 同事务写入 BOOKING_PAYMENT 流水与 wallet-events Outbox。
     */
    @Transactional
    public Mutation debit(Long userId, long amountCents, String bizType, String externalBizId,
            Long bookingId, Long checkoutId, String description) {
        requirePositive(amountCents);
        return mutate(userId, -amountCents, bizType, externalBizId, bookingId, checkoutId, description, true);
    }

    /** 退款 / 充值：amountCents > 0，加到余额。超出上限抛 409。 */
    @Transactional
    public Mutation credit(Long userId, long amountCents, String bizType, String externalBizId,
            Long bookingId, Long checkoutId, String description) {
        requirePositive(amountCents);
        return mutate(userId, amountCents, bizType, externalBizId, bookingId, checkoutId, description, true);
    }

    /**
     * 幂等入账（演示充值用）：以 externalBizId 去重。
     * 已记录过（或并发下被抢先记录）时返回 empty，且不改动余额；
     * 不同的业务键（用户主动发起的两笔充值）分别成功。
     * 唯一约束竞争通过 SAVEPOINT 回滚刚执行的余额 UPDATE，事务本身不受污染。
     */
    @Transactional
    public Optional<Mutation> creditOnce(Long userId, long amountCents, String bizType, String externalBizId,
            String description) {
        requirePositive(amountCents);
        if (ledgers.existsByExternalBizId(externalBizId)) {
            return Optional.empty();
        }
        jdbc.execute("SAVEPOINT wallet_credit_once");
        try {
            Mutation mutation = mutate(userId, amountCents, bizType, externalBizId, null, null, description, true);
            jdbc.execute("RELEASE SAVEPOINT wallet_credit_once");
            return Optional.of(mutation);
        }
        catch (DuplicateKeyException e) {
            jdbc.execute("ROLLBACK TO SAVEPOINT wallet_credit_once");
            return Optional.empty();
        }
    }

    /** 流水是否已存在（充值幂等的快速检查）。 */
    public boolean alreadyRecorded(String externalBizId) {
        return ledgers.existsByExternalBizId(externalBizId);
    }

    /**
     * 只写流水，不发 wallet-events（Seeder 的历史演示订单用；SQL 迁移的期初余额
     * 直接在迁移里写）。balanceAfter 必须由调用方按真实余额状态给出。
     * createdAt 由调用方给定，播种的历史流水才落在业务发生的时间上。
     */
    @Transactional
    public Mutation recordLedgerOnly(Long userId, long amountCents, String bizType, String externalBizId,
            Long bookingId, Long checkoutId, String description, long balanceBefore, long balanceAfter,
            long seqNo, Instant createdAt) {
        long ledgerId = insertHistoricalLedger(userId, amountCents, balanceBefore, balanceAfter,
                bizType, externalBizId, bookingId, checkoutId, description, seqNo, createdAt);
        return new Mutation(ledgerId, amountCents, balanceBefore, balanceAfter, seqNo);
    }

    /**
     * 原子余额变更 + 流水 + Outbox。delta 带符号。
     *
     * @param emitEvent false 时不写 wallet-events（Seeder / 期初数据，见 recordLedgerOnly）
     */
    private Mutation mutate(Long userId, long delta, String bizType, String externalBizId,
            Long bookingId, Long checkoutId, String description, boolean emitEvent) {
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE users
                SET wallet_cents = wallet_cents + ?,
                    ledger_seq = ledger_seq + 1
                WHERE id = ?
                  AND wallet_cents + ? BETWEEN 0 AND ?
                RETURNING wallet_cents, ledger_seq
                """, delta, userId, delta, MAX_WALLET_CENTS);
        if (rows.isEmpty()) {
            throw explainFailure(userId, delta);
        }
        long balanceAfter = ((Number) rows.get(0).get("wallet_cents")).longValue();
        long seqNo = ((Number) rows.get(0).get("ledger_seq")).longValue();
        long balanceBefore = balanceAfter - delta;
        long ledgerId = insertLedger(userId, delta, balanceBefore, balanceAfter,
                bizType, externalBizId, bookingId, checkoutId, description, seqNo, emitEvent);
        return new Mutation(ledgerId, delta, balanceBefore, balanceAfter, seqNo);
    }

    /** 历史流水的直插版本（调用方给定全部字段，包括 createdAt），不写 Outbox。 */
    private long insertHistoricalLedger(Long userId, long amountCents, long balanceBefore, long balanceAfter,
            String bizType, String externalBizId, Long bookingId, Long checkoutId,
            String description, long seqNo, Instant createdAt) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO wallet_ledger
                    (user_id, biz_type, amount_cents, balance_before_cents, balance_after_cents,
                     booking_id, checkout_id, external_biz_id, description, seq_no, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_biz_id) DO NOTHING
                RETURNING id
                """, Long.class,
                userId, bizType, amountCents, balanceBefore, balanceAfter,
                bookingId, checkoutId, externalBizId, description, seqNo,
                createdAt == null ? null : java.sql.Timestamp.from(createdAt));
        if (ids.isEmpty()) {
            throw BusinessException.conflict("Wallet entry already recorded: " + externalBizId);
        }
        return ids.get(0);
    }

    /**
     * 插入流水行。external_biz_id 全局唯一：
     * 竞争下后到的事务在这里失败并整体回滚（包括已执行的余额 UPDATE），
     * 这是「同一订单最多退款一次」的数据库级兜底。
     */
    private long insertLedger(Long userId, long amountCents, long balanceBefore, long balanceAfter,
            String bizType, String externalBizId, Long bookingId, Long checkoutId,
            String description, long seqNo, boolean emitEvent) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO wallet_ledger
                    (user_id, biz_type, amount_cents, balance_before_cents, balance_after_cents,
                     booking_id, checkout_id, external_biz_id, description, seq_no, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (external_biz_id) DO NOTHING
                RETURNING id
                """, Long.class,
                userId, bizType, amountCents, balanceBefore, balanceAfter,
                bookingId, checkoutId, externalBizId, description, seqNo);
        if (ids.isEmpty()) {
            // 流水已存在 = 该业务操作已经记过账（如并发下另一事务先完成了退款）。
            // 抛出让整个事务回滚：余额变更与流水要么同时生效，要么都不生效。
            throw BusinessException.conflict("Wallet entry already recorded: " + externalBizId);
        }
        long ledgerId = ids.get(0);
        if (emitEvent) {
            // wallet-events 是「已落库流水」的公告：消费者据此发提醒 / 统计，
            // 不得再次修改余额或生成核心流水。dedupKey 即业务去重标识。
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("messageId", java.util.UUID.randomUUID().toString());
            payload.put("eventType", "WALLET_LEDGER_RECORDED");
            payload.put("schemaVersion", 1);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("userId", userId);
            payload.put("ledgerId", ledgerId);
            payload.put("seqNo", seqNo);
            payload.put("bizType", bizType);
            payload.put("amountCents", amountCents);
            payload.put("balanceBeforeCents", balanceBefore);
            payload.put("balanceAfterCents", balanceAfter);
            if (bookingId != null) {
                payload.put("bookingId", bookingId);
            }
            if (checkoutId != null) {
                payload.put("checkoutId", checkoutId);
            }
            payload.put("dedupKey", externalBizId);
            outbox.write(KafkaTopics.WALLET_EVENTS, "WALLET_LEDGER_RECORDED",
                    "wallet:" + userId, externalBizId, payload);
        }
        return ledgerId;
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new BusinessException("Amount must be positive");
        }
    }

    private BusinessException explainFailure(Long userId, long delta) {
        List<Long> balances = jdbc.queryForList(
                "SELECT wallet_cents FROM users WHERE id = ?", Long.class, userId);
        if (balances.isEmpty()) {
            return BusinessException.notFound("User not found");
        }
        long current = balances.get(0);
        if (delta < 0 && current + delta < 0) {
            return BusinessException.conflict("Insufficient wallet balance");
        }
        return BusinessException.conflict("Wallet balance exceeds the limit");
    }

    /** 个人流水分页：数据库过滤 + 分页，类型与时间范围。 */
    public PageResult<LedgerVo> ledger(Long userId, String bizType, Instant from, Instant to, int page, int size) {
        String type = bizType == null || bizType.isBlank() ? null : bizType;
        Instant fromAt = from == null ? null : from;
        Instant toAt = to == null ? null : to;
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        long total = ledgers.searchCount(userId, type, fromAt, toAt);
        List<LedgerVo> records = ledgers.searchPage(userId, type, fromAt, toAt, safeSize, safePage * safeSize)
                .stream()
                .map(WalletService::toVo)
                .toList();
        return new PageResult<>(total, records);
    }

    public static LedgerVo toVo(WalletLedger ledger) {
        return new LedgerVo(
                ledger.getId(),
                ledger.getBizType(),
                ledger.getAmountCents(),
                ledger.getBalanceBeforeCents(),
                ledger.getBalanceAfterCents(),
                ledger.getBookingId(),
                ledger.getCheckoutId(),
                ledger.getDescription(),
                ledger.getSeqNo(),
                ledger.getCreatedAt());
    }
}
