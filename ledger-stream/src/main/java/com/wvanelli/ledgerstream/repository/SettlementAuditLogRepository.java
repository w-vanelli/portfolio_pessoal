package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.SettlementAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementAuditLogRepository extends JpaRepository<SettlementAuditLog, Long> {

    @Query("select a from SettlementAuditLog a where a.settlementEvent.id = :settlementEventId "
            + "order by a.createdAt asc, a.id asc")
    List<SettlementAuditLog> findBySettlementEventIdOrderByCreatedAtAsc(
            @Param("settlementEventId") Long settlementEventId);
}
