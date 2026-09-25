package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlement_projection")
public class SettlementProjection {

    @Id
    private UUID messageId;
    
    private Long settlementId;
    private String accountId;
    private String currency;
    private BigDecimal amount;
    private String settlementType;
    private Long originalSettlementId;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime processedAt;

    protected SettlementProjection() {}

    public SettlementProjection(UUID messageId, Long settlementId, String accountId, String currency, 
                                BigDecimal amount, String settlementType, Long originalSettlementId, 
                                OffsetDateTime acceptedAt, OffsetDateTime processedAt) {
        this.messageId = messageId;
        this.settlementId = settlementId;
        this.accountId = accountId;
        this.currency = currency;
        this.amount = amount;
        this.settlementType = settlementType;
        this.originalSettlementId = originalSettlementId;
        this.acceptedAt = acceptedAt;
        this.processedAt = processedAt;
    }

    public UUID getMessageId() { return messageId; }
    public Long getSettlementId() { return settlementId; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public String getSettlementType() { return settlementType; }
    public Long getOriginalSettlementId() { return originalSettlementId; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
}
