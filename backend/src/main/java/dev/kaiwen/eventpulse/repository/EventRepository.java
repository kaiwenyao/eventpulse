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
            WHERE id = :id AND sold >= :qty
            """, nativeQuery = true)
    int decrementSold(@Param("id") Long id, @Param("qty") int qty);
}
