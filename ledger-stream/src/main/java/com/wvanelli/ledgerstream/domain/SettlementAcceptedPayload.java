package com.wvanelli.ledgerstream.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementAcceptedPayload(
        UUID messageId,
        String schemaVersion,
        String eventType,
        Long settlementId,
        String settlementType,
        String accountId,
        String currency,
        String amount,
        Long originalSettlementId,
        OffsetDateTime acceptedAt,
        List<AttachmentMetadata> attachments
) {
    public record AttachmentMetadata(
            String originalFileName,
            String contentType,
            Long fileSizeBytes
    ) {}
}

