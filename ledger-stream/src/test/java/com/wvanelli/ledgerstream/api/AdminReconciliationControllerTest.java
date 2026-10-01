package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.application.ReconciliationReport;
import com.wvanelli.ledgerstream.application.SettlementReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReconciliationController.class)
class AdminReconciliationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementReconciliationService reconciliationService;

    @Test
    void reconcileReturnsServiceReport() throws Exception {
        var report = new ReconciliationReport(
                Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-09-26T12:00:01Z"),
                2, 3, 4, List.of("sample failure"));
        when(reconciliationService.reconcileAll()).thenReturn(report);

        mockMvc.perform(post("/api/v1/admin/reconcile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").value("2026-09-26T12:00:00Z"))
                .andExpect(jsonPath("$.completedAt").value("2026-09-26T12:00:01Z"))
                .andExpect(jsonPath("$.orphanDirectoriesRemoved").value(2))
                .andExpect(jsonPath("$.attachmentsReconciled").value(3))
                .andExpect(jsonPath("$.outboxRepairs").value(4))
                .andExpect(jsonPath("$.failures[0]").value("sample failure"));

        verify(reconciliationService).reconcileAll();
    }
}
