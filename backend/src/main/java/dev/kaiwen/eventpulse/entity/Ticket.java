package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "ticket_code_hash", nullable = false, unique = true)
    private String ticketCodeHash;

    @Column(name = "ticket_code_cipher", nullable = false)
    private String ticketCodeCipher;

    @Column(nullable = false)
    private String status;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_in_by")
    private Long checkedInBy;

    @Column(name = "check_in_source")
    private String checkInSource;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getTicketCodeHash() {
        return ticketCodeHash;
    }

    public void setTicketCodeHash(String ticketCodeHash) {
        this.ticketCodeHash = ticketCodeHash;
    }

    public String getTicketCodeCipher() {
        return ticketCodeCipher;
    }

    public void setTicketCodeCipher(String ticketCodeCipher) {
        this.ticketCodeCipher = ticketCodeCipher;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(Instant checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public Long getCheckedInBy() {
        return checkedInBy;
    }

    public void setCheckedInBy(Long checkedInBy) {
        this.checkedInBy = checkedInBy;
    }

    public String getCheckInSource() {
        return checkInSource;
    }

    public void setCheckInSource(String checkInSource) {
        this.checkInSource = checkInSource;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
