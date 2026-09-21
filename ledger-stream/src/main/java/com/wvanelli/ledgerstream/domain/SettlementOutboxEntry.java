package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable intent to publish an accepted settlement. The row is inserted in the same
 * transaction as the settlement event; a later dispatcher owns broker interaction.
 */
@Entity
@Table(name = "settlement_outbox")
public class SettlementOutboxEntry {
    @Id
    private UUID id;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private Long settlementId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload_checksum", nullable = false, length = 64)
    private String payloadChecksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected SettlementOutboxEntry() { }

    public SettlementOutboxEntry(SettlementEvent event) {
        Objects.requireNonNull(event, "event");
        this.id = UUID.randomUUID();
        this.settlementId = Objects.requireNonNull(event.getId(), "event must be persisted");
        this.idempotencyKey = event.getIdempotencyKey();
        this.eventType = "settlement.accepted";
        this.payloadChecksum = event.getPayloadChecksum();
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.availableAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = this.availableAt;
    }

    @PrePersist
    protected void onPrePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (availableAt == null) availableAt = createdAt;
        if (status == null) status = OutboxStatus.PENDING;
    }

    public UUID getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getEventType() { return eventType; }
    public String getPayloadChecksum() { return payloadChecksum; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getAvailableAt() { return availableAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public String getLastError() { return lastError; }

    public void markPublished(OffsetDateTime at) {
        if (status != OutboxStatus.PENDING) throw new IllegalStateException("Only pending entries can be published");
        status = OutboxStatus.PUBLISHED;
        publishedAt = Objects.requireNonNull(at, "publishedAt");
    }

    public void recordFailure(String error, OffsetDateTime retryAt) {
        if (status != OutboxStatus.PENDING) throw new IllegalStateException("Only pending entries can fail");
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        availableAt = Objects.requireNonNull(retryAt, "retryAt");
    }
}
