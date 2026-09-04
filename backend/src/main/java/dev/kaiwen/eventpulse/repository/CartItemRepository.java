package dev.kaiwen.eventpulse.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.CartItem;
import jakarta.persistence.LockModeType;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** 按加购时间倒序：created_at 只在首次加购时写入，改数量 / 勾选 /
     * 价格刷新都不会移动行；id 兜底保证同一毫秒加购的行顺序确定。 */
    List<CartItem> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<CartItem> findByIdAndUserId(Long id, Long userId);

    Optional<CartItem> findByUserIdAndEventId(Long userId, Long eventId);

    long countByUserId(Long userId);

    /**
     * 结算前锁住本用户的待结算购物车行：与并发修改（另一台设备改数量 / 移除）
     * 串行化，保证「只移除本次实际购买的数量、不误删后来新增的数量」。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CartItem c WHERE c.id IN :ids AND c.userId = :userId ORDER BY c.id")
    List<CartItem> lockByIdsAndUser(@Param("ids") Collection<Long> ids, @Param("userId") Long userId);

    /**
     * 结算移除分两步，避免把 quantity 减到 0 触发数据库 CHECK 约束：
     * 先整行删除「恰好买完全部数量」的项；留下的数量一定 >= 1。
     * 另一设备并发新增的数量会留在这行里，不会被误删。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM cart_items WHERE id = :id AND quantity = :qty", nativeQuery = true)
    int deleteIfPurchased(@Param("id") Long id, @Param("qty") int qty);

    /** 部分购买：只减去本次购买的数量，剩余数量保持 >= 1。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE CartItem c
               SET c.quantity = c.quantity - :qty, c.version = c.version + 1, c.updatedAt = :now
             WHERE c.id = :id AND c.quantity > :qty
            """)
    int decrementQuantity(@Param("id") Long id, @Param("qty") int qty, @Param("now") java.time.Instant now);

    /** 价格变化后重新确认：把价格快照刷新为当前价。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE cart_items
               SET price_cents = :price, version = version + 1, updated_at = now()
             WHERE id = :id AND user_id = :userId
            """, nativeQuery = true)
    int refreshPrice(@Param("id") Long id, @Param("userId") Long userId, @Param("price") int price);
}
