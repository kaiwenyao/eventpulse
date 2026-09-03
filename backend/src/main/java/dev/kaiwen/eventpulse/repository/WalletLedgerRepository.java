package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.WalletLedger;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

    boolean existsByExternalBizId(String externalBizId);

    Optional<WalletLedger> findByExternalBizId(String externalBizId);

    /** 批量取一组订单的退款流水：订单列表页用它展示已退款金额，避免逐单查询。 */
    List<WalletLedger> findByBookingIdInAndBizTypeIn(Collection<Long> bookingIds, Collection<String> bizTypes);

    List<WalletLedger> findByBookingIdInAndBizTypeInOrderById(Collection<Long> bookingIds, Collection<String> bizTypes);

    /**
     * 服务端分页的个人流水：类型与时间范围过滤在数据库完成，
     * 稳定排序 id DESC（同账户内 id 顺序与 seq_no 一致）。
     */
    @Query(value = """
            SELECT * FROM wallet_ledger
            WHERE user_id = :userId
              AND (CAST(:bizType AS VARCHAR) IS NULL OR biz_type = CAST(:bizType AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR created_at < CAST(:to AS TIMESTAMPTZ))
            ORDER BY id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<WalletLedger> searchPage(@Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*) FROM wallet_ledger
            WHERE user_id = :userId
              AND (CAST(:bizType AS VARCHAR) IS NULL OR biz_type = CAST(:bizType AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR created_at < CAST(:to AS TIMESTAMPTZ))
            """, nativeQuery = true)
    long searchCount(@Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
