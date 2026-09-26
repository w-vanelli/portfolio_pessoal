package com.wvanelli.ledgerstream.api.dto;

import com.wvanelli.ledgerstream.domain.SettlementProjection;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SettlementProjectionResponse(
        Long id,
        Long settlementId,
        UUID messageId,
        String accountId,
        String currency,
        String amount,
        String settlementType,
        Long originalSettlementId,
        OffsetDateTime acceptedAt,
        OffsetDateTime processedAt
) {
    public static SettlementProjectionResponse from(SettlementProjection projection) {
        return new SettlementProjectionResponse(
                projection.getSettlementId(),
                projection.getSettlementId(),
                projection.getMessageId(),
                projection.getAccountId(),
                projection.getCurrency(),
                projection.getAmount().toPlainString(),
                projection.getSettlementType(),
                projection.getOriginalSettlementId(),
                projection.getAcceptedAt(),
                projection.getProcessedAt()
        );
    }
}
