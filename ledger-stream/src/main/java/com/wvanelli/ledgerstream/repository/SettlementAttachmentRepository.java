package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link SettlementAttachment} entities.
 */
@Repository
public interface SettlementAttachmentRepository extends JpaRepository<SettlementAttachment, Long> {

    /**
     * Finds all attachments linked to a specific settlement event.
     */
    List<SettlementAttachment> findBySettlementEventId(Long settlementEventId);
}
