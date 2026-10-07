package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.api.dto.AuditLogEntry;
import com.wvanelli.ledgerstream.api.exception.ResourceNotFoundException;
import com.wvanelli.ledgerstream.application.SettlementAuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementAuditController {

    private final SettlementAuditQueryService queryService;

    public SettlementAuditController(SettlementAuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogEntry> getAudit(@PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Settlement ID must be a positive integer");
        }
        List<AuditLogEntry> entries = queryService.findBySettlementId(id);
        if (entries.isEmpty()) {
            throw new ResourceNotFoundException("Settlement audit with ID " + id + " not found");
        }
        return entries;
    }
}
