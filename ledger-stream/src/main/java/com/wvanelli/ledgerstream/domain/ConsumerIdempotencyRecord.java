package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "consumer_idempotency")
public class ConsumerIdempotencyRecord {

    @Id
    private UUID messageId;
    
    private OffsetDateTime processedAt;

    protected ConsumerIdempotencyRecord() {}

    public ConsumerIdempotencyRecord(UUID messageId, OffsetDateTime processedAt) {
        this.messageId = messageId;
        this.processedAt = processedAt;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }
}
