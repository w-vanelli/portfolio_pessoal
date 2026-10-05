package com.wvanelli.ledgerstream.api.dto;

import com.wvanelli.ledgerstream.domain.SettlementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ingestion request payload for financial settlement events.
 * Maps to components/schemas/SettlementIngestRequest in docs/openapi.yaml.
 */
public record SettlementIngestRequest(
        @NotBlank(message = "accountId is required")
        @Size(max = 64, message = "accountId must not exceed 64 characters")
        String accountId,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be 3 characters")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code")
        String currency,

        @NotBlank(message = "amount is required")
        @Pattern(regexp = "^(0|[1-9]\\d{0,12})(\\.\\d{1,2})?$", message = "amount must conform to NUMERIC(15,2) format")
        String amount,

        @NotNull(message = "settlementType is required")
        SettlementType settlementType,

        Long originalSettlementId,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description
) {
    public SettlementIngestRequest(String accountId, String currency, String amount, SettlementType settlementType) {
        this(accountId, currency, amount, settlementType, null, null);
    }
}
