package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.SettlementProjection;
import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

public interface SettlementProjectionRepository extends CrudRepository<SettlementProjection, UUID> {
    List<SettlementProjection> findByAccountId(String accountId);
}
