package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusOrderByStartsAtAsc(String status);

    List<Event> findByStatusInOrderByStartsAtAsc(Collection<String> statuses);

    List<Event> findByOrganiserIdOrderByStartsAtDesc(Long organiserId);

    List<Event> findByOrganiserIdAndStatus(Long organiserId, String status);

    Optional<Event> findByIdAndOrganiserId(Long id, Long organiserId);

    List<Event> findByStatusAndStartsAtLessThanEqual(String status, Instant now);

    List<Event> findByStatusAndEndsAtLessThanEqual(String status, Instant now);

    /**
     * 数据库条件更新：开始时间已到、状态仍为 PUBLISHED 的活动改为 ONGOING。
     * 多个 Worker 同时执行时，后执行的一方只会更新 0 行，不会覆盖新状态，
     * 也不会产生乐观锁冲突。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Event e
               set e.status = :ongoing, e.updatedAt = :now
             where e.status = :published
               and e.startsAt <= :now
            """)
    int startPublishedEvents(@Param("published") String published, @Param("ongoing") String ongoing,
            @Param("now") Instant now);

    /** 数据库条件更新：结束时间已到、状态仍为 ONGOING 的活动改为 FINISHED。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Event e
               set e.status = :finished, e.updatedAt = :now
             where e.status = :ongoing
               and e.endsAt <= :now
            """)
    int finishOngoingEvents(@Param("ongoing") String ongoing, @Param("finished") String finished,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE events
            SET sold = sold + :qty, updated_at = now()
            WHERE id = :id
              AND status = 'PUBLISHED'
              AND sold + :qty <= capacity
            """, nativeQuery = true)
    int incrementSold(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE events
            SET sold = sold - :qty, updated_at = now()
            WHERE id = :id
              AND status = 'PUBLISHED'
              AND starts_at > now()
              AND sold >= :qty
            """, nativeQuery = true)
    int decrementSoldForCustomerCancellation(@Param("id") Long id, @Param("qty") int qty);
}
