package dev.kaiwen.eventpulse.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_favourites")
@IdClass(EventFavourite.Key.class)
public class EventFavourite {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public EventFavourite() {
    }

    public EventFavourite(Long userId, Long eventId) {
        this.userId = userId;
        this.eventId = eventId;
        this.createdAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public static class Key implements Serializable {
        private Long userId;
        private Long eventId;

        public Key() {
        }

        public Key(Long userId, Long eventId) {
            this.userId = userId;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId) && Objects.equals(eventId, key.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, eventId);
        }
    }
}
