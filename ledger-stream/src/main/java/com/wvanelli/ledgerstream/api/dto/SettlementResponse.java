package com.wvanelli.ledgerstream.api.dto;

import com.wvanelli.ledgerstream.domain.SettlementEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response payload representing an accepted or replayed settlement event.
 * Maps to components/schemas/SettlementResponse in docs/openapi.yaml.
 */
public record SettlementResponse(
        Long id,
        UUID idempotencyKey,
        String accountId,
        String currency,
        String amount,
        String status,
        boolean hasAttachment,
        String attachmentFileName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static SettlementResponse from(SettlementEvent event) {
        boolean hasAttachment = event.getAttachments() != null && !event.getAttachments().isEmpty();
        String attachmentFileName = hasAttachment ? event.getAttachments().get(0).getOriginalFileName() : null;
        return new SettlementResponse(
                event.getId(),
                event.getIdempotencyKey(),
                event.getAccountId(),
                event.getCurrency(),
                event.getAmount().toPlainString(),
                event.getStatus().name(),
                hasAttachment,
                attachmentFileName,
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }
}
