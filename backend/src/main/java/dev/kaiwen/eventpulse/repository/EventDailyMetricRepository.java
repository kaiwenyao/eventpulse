package dev.kaiwen.eventpulse.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.EventDailyMetric;

public interface EventDailyMetricRepository extends JpaRepository<EventDailyMetric, EventDailyMetric.Key> {

    List<EventDailyMetric> findByEventIdAndMetricDateBetween(Long eventId, LocalDate from, LocalDate to);

    List<EventDailyMetric> findByEventIdInAndMetricDateBetween(List<Long> eventIds, LocalDate from, LocalDate to);

    /**
     * 数据库原子加一：当天还没有统计行时，INSERT 里已经带 1（首次即 +1）；
     * 已有行时 ON CONFLICT 只把对应列 +1。并发下不会互相覆盖。
     * tickets 同时增加实际预订张数 quantity（一次订 4 张就记 4 张）。
     */
    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 0, 0, 0, 0, 1, :quantity, 0, 0)
            on conflict (event_id, metric_date) do update
              set bookings = event_daily_metrics.bookings + 1,
                  tickets  = event_daily_metrics.tickets  + :quantity
            """, nativeQuery = true)
    void incrementBookings(@Param("eventId") Long eventId, @Param("date") LocalDate date,
                           @Param("quantity") int quantity);

    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 0, 0, 0, 0, 0, 0, 1, 0)
            on conflict (event_id, metric_date) do update
              set cancels = event_daily_metrics.cancels + 1
            """, nativeQuery = true)
    void incrementCancels(@Param("eventId") Long eventId, @Param("date") LocalDate date);

    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 1, 0, 0, 0, 0, 0, 0, 0)
            on conflict (event_id, metric_date) do update
              set views = event_daily_metrics.views + 1
            """, nativeQuery = true)
    void incrementViews(@Param("eventId") Long eventId, @Param("date") LocalDate date);

    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 0, 1, 0, 0, 0, 0, 0, 0)
            on conflict (event_id, metric_date) do update
              set clicks = event_daily_metrics.clicks + 1
            """, nativeQuery = true)
    void incrementClicks(@Param("eventId") Long eventId, @Param("date") LocalDate date);

    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 0, 0, 1, 0, 0, 0, 0, 0)
            on conflict (event_id, metric_date) do update
              set saves = event_daily_metrics.saves + 1
            """, nativeQuery = true)
    void incrementSaves(@Param("eventId") Long eventId, @Param("date") LocalDate date);

    @Modifying
    @Query(value = """
            insert into event_daily_metrics (event_id, metric_date, views, clicks, saves, unsaves,
                                             bookings, tickets, cancels, check_ins)
            values (:eventId, :date, 0, 0, 0, 1, 0, 0, 0, 0)
            on conflict (event_id, metric_date) do update
              set unsaves = event_daily_metrics.unsaves + 1
            """, nativeQuery = true)
    void incrementUnsaves(@Param("eventId") Long eventId, @Param("date") LocalDate date);
}
