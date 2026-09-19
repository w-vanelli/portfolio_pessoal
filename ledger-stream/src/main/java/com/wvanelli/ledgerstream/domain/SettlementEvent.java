package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Root aggregate entity representing a financial settlement ingestion event.
 * Mapped to table {@code settlement_events}.
 */
@Entity
@Table(name = "settlement_events")
public class SettlementEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "payload_checksum", nullable = false, length = 64)
    private String payloadChecksum;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 32)
    private SettlementType settlementType;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SettlementStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Optional reference to the original settlement being adjusted.
     * Used exclusively for {@link SettlementType#CHARGEBACK_ADJUSTMENT} events.
     *
     * <p>Design decision (approved): Chargeback adjustments reference an existing settlement
     * of a different type, belonging to the same account and currency, with a strictly positive
     * amount representing the absolute value of the adjustment. The original settlement is
     * never modified or deleted. Validation of these business rules is performed by the
     * application service layer, not by this entity.
     *
     * <p>Limitation: This project does not control cumulative refundable balance nor
     * certify the financial eligibility of chargebacks. LedgerStream records and dispatches
     * the adjustment event; it does not execute transfers, debits, credits, or settlements.
     */
    @Column(name = "original_settlement_id")
    private Long originalSettlementId;

    @OneToMany(mappedBy = "settlementEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SettlementAttachment> attachments = new ArrayList<>();

    protected SettlementEvent() {
        // Required by JPA specification
    }

    public SettlementEvent(UUID idempotencyKey,
                           String payloadChecksum,
                           String accountId,
                           String currency,
                           MonetaryAmount monetaryAmount,
                           SettlementType settlementType,
                           String description) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.payloadChecksum = Objects.requireNonNull(payloadChecksum, "payloadChecksum must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.currency = validateCurrency(currency);
        Objects.requireNonNull(monetaryAmount, "monetaryAmount must not be null");
        this.amount = monetaryAmount.getValue();
        this.settlementType = Objects.requireNonNull(settlementType, "settlementType must not be null");
        this.description = description;
        this.status = SettlementStatus.STAGED;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        }
        if (this.status == null) {
            this.status = SettlementStatus.STAGED;
        }
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String validateCurrency(String currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        String trimmed = currency.trim().toUpperCase();
        if (trimmed.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code: " + currency);
        }
        return trimmed;
    }

    public void addAttachment(SettlementAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment must not be null");
        this.attachments.add(attachment);
        attachment.setSettlementEvent(this);
    }

    public void markAsChargebackOf(Long originalSettlementId) {
        if (this.settlementType != SettlementType.CHARGEBACK_ADJUSTMENT) {
            throw new IllegalStateException("Only CHARGEBACK_ADJUSTMENT can reference an original settlement.");
        }
        this.originalSettlementId = Objects.requireNonNull(originalSettlementId, "originalSettlementId must not be null");
    }

    public void transitionTo(SettlementStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        if (this.status == newStatus) {
            // "Permanecer no mesmo estado não deve gerar uma transição fictícia nem apagar a informação da falha."
            return;
        }

        boolean valid = switch (this.status) {
            case STAGED -> newStatus == SettlementStatus.COMMITTED || newStatus == SettlementStatus.FAILED || newStatus == SettlementStatus.COMPENSATED;
            case FAILED -> newStatus == SettlementStatus.COMPENSATED;
            case COMMITTED -> newStatus == SettlementStatus.DISPATCHED;
            case DISPATCHED, COMPENSATED -> false;
        };

        if (!valid) {
            throw new IllegalStateException(String.format("Invalid state transition from %s to %s", this.status, newStatus));
        }

        this.status = newStatus;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // Getters
    public Long getId() {
        return id;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadChecksum() {
        return payloadChecksum;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public MonetaryAmount getMonetaryAmount() {
        return new MonetaryAmount(amount);
    }

    public SettlementType getSettlementType() {
        return settlementType;
    }

    public String getDescription() {
        return description;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Returns the ID of the original settlement referenced by a chargeback adjustment,
     * or {@code null} if this event is not a chargeback adjustment.
     */
    public Long getOriginalSettlementId() {
        return originalSettlementId;
    }

    public List<SettlementAttachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }
}
