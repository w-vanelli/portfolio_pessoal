package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.SettlementProjection;
import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementProjectionRepository extends CrudRepository<SettlementProjection, UUID> {
    Optional<SettlementProjection> findBySettlementId(Long settlementId);
    Optional<SettlementProjection> findFirstBySettlementIdOrderByProcessedAtDesc(Long settlementId);
    List<SettlementProjection> findByAccountId(String accountId);
    List<SettlementProjection> findByAccountIdOrderByAcceptedAtDesc(String accountId);
}


