package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.OutboxStatus;
import com.wvanelli.ledgerstream.domain.SettlementOutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementOutboxRepository extends JpaRepository<SettlementOutboxEntry, UUID> {
    Optional<SettlementOutboxEntry> findBySettlementId(Long settlementId);
    List<SettlementOutboxEntry> findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
            OutboxStatus status, OffsetDateTime now);
}
