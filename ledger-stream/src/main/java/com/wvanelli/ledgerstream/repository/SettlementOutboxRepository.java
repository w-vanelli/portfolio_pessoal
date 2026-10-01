package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.OutboxStatus;
import com.wvanelli.ledgerstream.domain.SettlementOutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementOutboxRepository extends JpaRepository<SettlementOutboxEntry, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SettlementOutboxEntry e where e.id = :id")
    Optional<SettlementOutboxEntry> findLockedById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SettlementOutboxEntry e where e.settlementId = :settlementId")
    Optional<SettlementOutboxEntry> findLockedBySettlementId(@Param("settlementId") Long settlementId);
    Optional<SettlementOutboxEntry> findBySettlementId(Long settlementId);
    List<SettlementOutboxEntry> findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
            OutboxStatus status, OffsetDateTime now);
}
