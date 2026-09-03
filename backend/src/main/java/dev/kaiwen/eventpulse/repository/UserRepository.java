package dev.kaiwen.eventpulse.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Atomically deducts a wallet balance only when sufficient funds exist.  A return value of
     * zero means the user has insufficient funds (or no longer exists), so callers must not
     * continue with the order.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET wallet_cents = wallet_cents - :amount
            WHERE id = :userId
              AND wallet_cents >= :amount
            """, nativeQuery = true)
    int debitWalletIfEnough(@Param("userId") Long userId, @Param("amount") long amount);

    /** Atomically adds funds without letting the balance exceed the configured cap. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET wallet_cents = wallet_cents + :amount
            WHERE id = :userId
              AND wallet_cents <= :maxBalance - :amount
            """, nativeQuery = true)
    int rechargeWalletWithinLimit(
            @Param("userId") Long userId,
            @Param("amount") long amount,
            @Param("maxBalance") long maxBalance);

    /** 播种专用：把流水计数推进到已写入的历史流水序号（真实交易总是 +1 递增）。 */
    @Modifying
    @Query("UPDATE User u SET u.ledgerSeq = :seq WHERE u.id = :id")
    int updateLedgerSeq(@Param("id") Long id, @Param("seq") long seq);

    /** Refunds an already-recorded payment amount. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET wallet_cents = wallet_cents + :amount
            WHERE id = :userId
            """, nativeQuery = true)
    int creditWallet(@Param("userId") Long userId, @Param("amount") long amount);
}
