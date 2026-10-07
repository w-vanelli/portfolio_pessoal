package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.api.dto.AuditLogEntry;
import com.wvanelli.ledgerstream.repository.SettlementAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class SettlementAuditQueryService {

    private final SettlementAuditLogRepository repository;

    public SettlementAuditQueryService(SettlementAuditLogRepository repository) {
        this.repository = repository;
    }

    public List<AuditLogEntry> findBySettlementId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Settlement ID must be a positive integer");
        }
        return repository.findBySettlementEventIdOrderByCreatedAtAsc(id).stream()
                .map(AuditLogEntry::from).toList();
    }
}
