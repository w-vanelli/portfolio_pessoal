package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.api.dto.SettlementProjectionResponse;
import com.wvanelli.ledgerstream.api.exception.ResourceNotFoundException;
import com.wvanelli.ledgerstream.application.SettlementProjectionQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementQueryController {

    private final SettlementProjectionQueryService queryService;

    public SettlementQueryController(SettlementProjectionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<SettlementProjectionResponse> getById(@PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Settlement ID must be a positive integer");
        }
        return queryService.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement projection with ID " + id + " not found"));
    }


    @GetMapping("/idempotency/{key}")
    public ResponseEntity<SettlementProjectionResponse> getByIdempotencyKey(
            @PathVariable("key") UUID key) {
        return queryService.findByIdempotencyKey(key)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Settlement projection with idempotency key " + key + " not found"));
    }

    @GetMapping
    public ResponseEntity<List<SettlementProjectionResponse>> getByAccountId(
            @RequestParam(name = "accountId", required = false) String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'accountId' is required");
        }
        List<SettlementProjectionResponse> results = queryService.findByAccountId(accountId);
        return ResponseEntity.ok(results);
    }
}
