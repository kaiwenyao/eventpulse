package dev.kaiwen.eventpulse.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_daily_metrics")
@IdClass(EventDailyMetric.Key.class)
public class EventDailyMetric {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Id
    @Column(name = "metric_date")
    private LocalDate metricDate;

    @Column(nullable = false)
    private int views;

    @Column(nullable = false)
    private int clicks;

    @Column(nullable = false)
    private int saves;

    @Column(nullable = false)
    private int unsaves;

    @Column(nullable = false)
    private int bookings;

    @Column(nullable = false)
    private int tickets;

    @Column(nullable = false)
    private int cancels;

    @Column(name = "check_ins", nullable = false)
    private int checkIns;

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public LocalDate getMetricDate() {
        return metricDate;
    }

    public void setMetricDate(LocalDate metricDate) {
        this.metricDate = metricDate;
    }

    public int getViews() {
        return views;
    }

    public void setViews(int views) {
        this.views = views;
    }

    public int getClicks() {
        return clicks;
    }

    public void setClicks(int clicks) {
        this.clicks = clicks;
    }

    public int getSaves() {
        return saves;
    }

    public void setSaves(int saves) {
        this.saves = saves;
    }

    public int getUnsaves() {
        return unsaves;
    }

    public void setUnsaves(int unsaves) {
        this.unsaves = unsaves;
    }

    public int getBookings() {
        return bookings;
    }

    public void setBookings(int bookings) {
        this.bookings = bookings;
    }

    public int getTickets() {
        return tickets;
    }

    public void setTickets(int tickets) {
        this.tickets = tickets;
    }

    public int getCancels() {
        return cancels;
    }

    public void setCancels(int cancels) {
        this.cancels = cancels;
    }

    public int getCheckIns() {
        return checkIns;
    }

    public void setCheckIns(int checkIns) {
        this.checkIns = checkIns;
    }

    public static class Key implements Serializable {
        private Long eventId;
        private LocalDate metricDate;

        public Key() {
        }

        public Key(Long eventId, LocalDate metricDate) {
            this.eventId = eventId;
            this.metricDate = metricDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId) && Objects.equals(metricDate, key.metricDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, metricDate);
        }
    }
}
