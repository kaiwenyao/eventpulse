package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 原子领取一批待发送消息：一条 UPDATE 里完成筛选与打标记，两个 Worker 不会
     * 领到同一条（PostgreSQL 行级互斥保证只有一个 UPDATE 修改该行）。
     *
     * 筛选条件：
     * - 未发布、未隔离；
     * - 没有生效中的领取租约（claimed_until 为空或已过期，Worker 崩溃后可接手）；
     * - 顺序规则：同一个 message_key 若还有更早且未发布的消息，则本条暂时不领
     *   （同一订单的「创建」没发出去之前，不能先发「取消」）。已被隔离的更早消息不阻塞。
     *
     * 子查询带 FOR UPDATE SKIP LOCKED：并发领取时直接跳过对方锁住的行。
     * 没有它，外层 UPDATE 的「id IN (子查询)」在锁等待后只会用旧快照复核，
     * 两个 Worker 会各自认为自己领取成功。
     *
     * 领取标记写入调用方传入的一次性 token，之后用 {@link #findByClaimedByOrderByIdAsc(String)}
     * 取回本批消息；token 随机且每次领取不同，避免取回别的 Worker 的批次。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET claimed_by = :token,
                   claimed_until = :until
             WHERE id IN (
                   SELECT id
                     FROM outbox
                    WHERE published_at IS NULL
                      AND failed_at IS NULL
                      AND (claimed_until IS NULL OR claimed_until < :now)
                      AND NOT EXISTS (
                          SELECT 1
                            FROM outbox earlier
                           WHERE earlier.message_key = outbox.message_key
                             AND earlier.id < outbox.id
                             AND earlier.published_at IS NULL
                             AND earlier.failed_at IS NULL)
                    ORDER BY id
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int claimBatch(@Param("token") String token, @Param("until") Instant until,
            @Param("now") Instant now, @Param("batch") int batch);

    /** 按领取 token 取回本轮领到的消息，按 id 升序（保持写入顺序）。 */
    List<OutboxEvent> findByClaimedByOrderByIdAsc(String claimedBy);

    /**
     * 释放一条消息的领取（发送失败后让其他 Worker 可以立刻接手，而不是等租约到期）。
     * 只释放仍归本 token 所有的行。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o
               set o.claimedBy = null,
                   o.claimedUntil = null
             where o.claimedBy = :token
               and o.id = :id
            """)
    int releaseClaim(@Param("token") String token, @Param("id") Long id);

    /**
     * Kafka 明确确认成功后，用一条带条件的 UPDATE 打标记，同时清掉领取信息。
     * 行已标记、已隔离或已被删除时更新 0 行，不抛异常。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o
               set o.publishedAt = :now,
                   o.claimedBy = null,
                   o.claimedUntil = null,
                   o.lastError = null
             where o.id = :id
               and o.publishedAt is null
               and o.failedAt is null
            """)
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 发送失败时递增尝试次数；如果决定隔离，同时写 failed_at。
     * 只有仍处于待发送状态的行才会更新。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o
               set o.publishAttempts = o.publishAttempts + 1,
                   o.lastError = :lastError,
                   o.failedAt = coalesce(o.failedAt, :failedAt)
             where o.id = :id
               and o.publishedAt is null
            """)
    int recordFailure(@Param("id") Long id, @Param("lastError") String lastError,
            @Param("failedAt") Instant failedAt);

    long countByPublishedAtIsNullAndFailedAtIsNull();

    long countByFailedAtIsNotNull();

    /**
     * 最老的待发送消息已经等待的秒数；没有待发送消息时返回 null。
     */
    @Query(value = """
            select extract(epoch from (now() - min(created_at)))
              from outbox
             where published_at is null
               and failed_at is null
            """, nativeQuery = true)
    Double secondsSinceOldestPending();
}
