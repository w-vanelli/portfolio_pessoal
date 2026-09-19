package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link SettlementEvent} aggregates.
 */
@Repository
public interface SettlementEventRepository extends JpaRepository<SettlementEvent, Long> {

    /**
     * Finds a settlement event by its unique idempotency key.
     */
    Optional<SettlementEvent> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Checks if a settlement event exists with the given idempotency key.
     */
    boolean existsByIdempotencyKey(UUID idempotencyKey);

    /**
     * Finds all settlement events for a specific tenant/merchant account filtered by status.
     */
    List<SettlementEvent> findByAccountIdAndStatus(String accountId, SettlementStatus status);
}
