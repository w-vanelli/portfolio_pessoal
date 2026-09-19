package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.SettlementType;

import java.io.InputStream;
import java.util.UUID;

public record RegisterSettlementCommand(
        UUID idempotencyKey,
        String accountId,
        String currency,
        String amount,
        SettlementType settlementType,
        String description,
        Long originalSettlementId,
        String attachmentFileName,
        String attachmentContentType,
        InputStream attachmentStream
) {
}
