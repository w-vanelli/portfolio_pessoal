package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.api.dto.AuditLogEntry;
import com.wvanelli.ledgerstream.application.SettlementAuditQueryService;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementAuditController.class)
class SettlementAuditControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private SettlementAuditQueryService queryService;

    @Test
    void shouldReturnOrderedDtoHistory() throws Exception {
        var timestamp = OffsetDateTime.parse("2026-10-07T12:00:00Z");
        when(queryService.findBySettlementId(42L)).thenReturn(List.of(
                new AuditLogEntry(1L, 42L, null, SettlementStatus.STAGED, null, timestamp),
                new AuditLogEntry(2L, 42L, SettlementStatus.STAGED, SettlementStatus.COMMITTED,
                        "Accepted", timestamp)));
        mockMvc.perform(get("/api/v1/settlements/42/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].*", hasSize(6)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].settlementId").value(42))
                .andExpect(jsonPath("$[0].previousStatus").value(nullValue()))
                .andExpect(jsonPath("$[0].newStatus").value("STAGED"))
                .andExpect(jsonPath("$[0].eventDetails").value(nullValue()))
                .andExpect(jsonPath("$[0].timestamp").value("2026-10-07T12:00:00Z"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].previousStatus").value("STAGED"))
                .andExpect(jsonPath("$[1].newStatus").value("COMMITTED"))
                .andExpect(jsonPath("$[1].eventDetails").value("Accepted"))
                .andExpect(jsonPath("$[1].settlementEvent").doesNotExist());
    }

    @Test
    void shouldReturn404ForMissingHistory() throws Exception {
        when(queryService.findBySettlementId(42L)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/settlements/42/audit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Settlement audit with ID 42 not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements/42/audit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "1.5", "9223372036854775808"})
    void shouldRejectInvalidIdsBeforeQuery(String id) throws Exception {
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
        verifyNoInteractions(queryService);
    }
}
