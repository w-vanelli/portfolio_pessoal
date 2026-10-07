package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** A lifecycle entry owned and created exclusively by its settlement aggregate. */
@Entity
@Table(name = "settlement_audit_log")
public class SettlementAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_event_id", nullable = false, updatable = false)
    private SettlementEvent settlementEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32, updatable = false)
    private SettlementStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32, updatable = false)
    private SettlementStatus newStatus;

    @Column(name = "event_details", columnDefinition = "text", updatable = false)
    private String eventDetails;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SettlementAuditLog() {
        // JPA only: loading an existing entry must not create history.
    }

    SettlementAuditLog(SettlementEvent settlementEvent, SettlementStatus previousStatus,
                       SettlementStatus newStatus, String eventDetails, OffsetDateTime createdAt) {
        this.settlementEvent = Objects.requireNonNull(settlementEvent, "settlementEvent");
        this.previousStatus = previousStatus;
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus");
        this.eventDetails = eventDetails;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").withOffsetSameInstant(ZoneOffset.UTC);
    }

    public Long getId() { return id; }
    public SettlementEvent getSettlementEvent() { return settlementEvent; }
    public SettlementStatus getPreviousStatus() { return previousStatus; }
    public SettlementStatus getNewStatus() { return newStatus; }
    public String getEventDetails() { return eventDetails; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
