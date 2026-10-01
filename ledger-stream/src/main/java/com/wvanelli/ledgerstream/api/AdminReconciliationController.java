package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.application.ReconciliationReport;
import com.wvanelli.ledgerstream.application.SettlementReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReconciliationController {

    private final SettlementReconciliationService reconciliationService;

    public AdminReconciliationController(SettlementReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    public ResponseEntity<ReconciliationReport> reconcile() {
        return ResponseEntity.ok(reconciliationService.reconcileAll());
    }
}
