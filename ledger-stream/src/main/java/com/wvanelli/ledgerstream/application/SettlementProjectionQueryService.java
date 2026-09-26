package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.api.dto.SettlementProjectionResponse;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class SettlementProjectionQueryService {

    private final SettlementProjectionRepository projectionRepository;

    public SettlementProjectionQueryService(SettlementProjectionRepository projectionRepository) {
        this.projectionRepository = projectionRepository;
    }

    public Optional<SettlementProjectionResponse> findById(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Settlement ID must be a positive integer");
        }
        return projectionRepository.findFirstBySettlementIdOrderByProcessedAtDesc(id)
                .map(SettlementProjectionResponse::from);

    }

    public List<SettlementProjectionResponse> findByAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID must not be null or blank");
        }
        return projectionRepository.findByAccountIdOrderByAcceptedAtDesc(accountId.trim())
                .stream()
                .map(SettlementProjectionResponse::from)
                .toList();
    }
}
