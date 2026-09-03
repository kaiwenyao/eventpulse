package dev.kaiwen.eventpulse.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.Checkout;

public interface CheckoutRepository extends JpaRepository<Checkout, Long> {

    Optional<Checkout> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * 在结算事务开始时原子登记幂等键：
     * - 返回新行 id：本次调用赢得键，继续结算；
     * - 返回空：同键结算已存在（或正被并发事务执行），调用方读取既有行决定
     *   返回原订单（参数一致）还是拒绝（参数不同）。
     * 结算失败时整个事务回滚，键行一并消失，同一键的重试可以重新结算。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO checkouts (user_id, idempotency_key, request_hash, status, created_at)
            VALUES (:userId, :key, :hash, 'SUCCEEDED', now())
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    List<Long> insertIfAbsent(@Param("userId") Long userId,
            @Param("key") String idempotencyKey,
            @Param("hash") String requestHash);
}
