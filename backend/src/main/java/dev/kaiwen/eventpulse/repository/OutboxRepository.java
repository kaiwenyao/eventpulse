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
     * 待发送：published_at 为空且没有被隔离的消息，按 id 升序（保持写入顺序）。
     */
    List<OutboxEvent> findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc();

    /**
     * Kafka 明确确认成功后，用一条带条件的 UPDATE 打标记。
     * 行已标记、已隔离或已被删除时更新 0 行，不抛异常。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent o
               set o.publishedAt = :now,
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