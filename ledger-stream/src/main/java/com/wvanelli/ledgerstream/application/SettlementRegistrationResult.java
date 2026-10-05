package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of registering a settlement event, indicating whether it was freshly created
 * or served as an idempotent replay.
 */
public record SettlementRegistrationResult(
        SettlementEvent event,
        boolean replay
) {
    public SettlementRegistrationResult {
        Objects.requireNonNull(event, "event must not be null");
    }

    public static SettlementRegistrationResult created(SettlementEvent event) {
        return new SettlementRegistrationResult(event, false);
    }

    public static SettlementRegistrationResult replay(SettlementEvent event) {
        return new SettlementRegistrationResult(event, true);
    }

    public boolean isNew() {
        return !replay;
    }

    public boolean isReplay() {
        return replay;
    }

    public Long getId() {
        return event.getId();
    }

    public Long getOriginalSettlementId() {
        return event.getOriginalSettlementId();
    }

    public UUID getIdempotencyKey() {
        return event.getIdempotencyKey();
    }

    public String getPayloadChecksum() {
        return event.getPayloadChecksum();
    }

    public SettlementStatus getStatus() {
        return event.getStatus();
    }
}
