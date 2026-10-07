package com.wvanelli.ledgerstream.api.dto;

import com.wvanelli.ledgerstream.domain.SettlementAuditLog;
import com.wvanelli.ledgerstream.domain.SettlementStatus;

import java.time.OffsetDateTime;

public record AuditLogEntry(Long id, Long settlementId, SettlementStatus previousStatus,
                            SettlementStatus newStatus, String eventDetails, OffsetDateTime timestamp) {

    public static AuditLogEntry from(SettlementAuditLog entry) {
        return new AuditLogEntry(entry.getId(), entry.getSettlementEvent().getId(),
                entry.getPreviousStatus(), entry.getNewStatus(), entry.getEventDetails(), entry.getCreatedAt());
    }
}
