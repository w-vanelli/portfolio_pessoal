package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminReconciliationControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void reconcileRunsAgainstApplicationAndIncrementsMetric() throws Exception {
        double before = registry.get("ledgerstream.reconciliation.runs").counter().count();

        mockMvc.perform(post("/api/v1/admin/reconcile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.orphanDirectoriesRemoved").isNumber())
                .andExpect(jsonPath("$.attachmentsReconciled").isNumber())
                .andExpect(jsonPath("$.outboxRepairs").isNumber())
                .andExpect(jsonPath("$.failures").isArray());

        assertThat(registry.get("ledgerstream.reconciliation.runs").counter().count())
                .isEqualTo(before + 1);
    }
}
