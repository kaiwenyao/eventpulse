package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Booking> findByEventIdOrderByCreatedAtDesc(Long eventId);

    List<Booking> findByCheckoutIdOrderByIdAsc(Long checkoutId);

    long countByEventIdAndStatus(Long eventId, String status);

    /**
     * Claims a confirmed booking for cancellation exactly once.  This prevents two concurrent
     * cancellation requests from both refunding the same payment.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
            SET status = 'CANCELLED', cancelled_at = now()
            WHERE id = :id AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int cancelConfirmed(@Param("id") Long id);

    /**
     * 历史订单的服务端分页查询：状态、时间范围与搜索（订单号精确 / 活动名模糊）
     * 全部在数据库完成；排序 created_at DESC, id DESC —— 时间相同用 id 保证稳定次级排序。
     * :qNumeric 只在搜索词是纯数字时按订单号精确匹配，否则用不可能的 -1 使该分支恒假。
     */
    @Query(value = """
            SELECT * FROM bookings b
            WHERE b.user_id = :userId
              AND (CAST(:status AS VARCHAR) IS NULL OR b.status = CAST(:status AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR b.created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR b.created_at < CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:q AS VARCHAR) IS NULL OR b.id = CAST(:qNumeric AS BIGINT) OR EXISTS (
                    SELECT 1 FROM events e
                    WHERE e.id = b.event_id AND e.title ILIKE CAST(:qLike AS VARCHAR)))
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Booking> searchPage(@Param("userId") Long userId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("q") String q,
            @Param("qNumeric") long qNumeric,
            @Param("qLike") String qLike,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*) FROM bookings b
            WHERE b.user_id = :userId
              AND (CAST(:status AS VARCHAR) IS NULL OR b.status = CAST(:status AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR b.created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR b.created_at < CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:q AS VARCHAR) IS NULL OR b.id = CAST(:qNumeric AS BIGINT) OR EXISTS (
                    SELECT 1 FROM events e
                    WHERE e.id = b.event_id AND e.title ILIKE CAST(:qLike AS VARCHAR)))
            """, nativeQuery = true)
    long searchCount(@Param("userId") Long userId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("q") String q,
            @Param("qNumeric") long qNumeric,
            @Param("qLike") String qLike);

    /** 一次取回整页订单涉及的活动，避免逐单查活动（N+1）。 */
    @Query("""
            SELECT e FROM Event e WHERE e.id IN :ids
            """)
    List<dev.kaiwen.eventpulse.entity.Event> findEventsByIds(@Param("ids") Collection<Long> ids);
}
