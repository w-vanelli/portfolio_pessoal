package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.domain.SettlementProjection;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class SettlementQueryControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SettlementProjectionRepository projectionRepository;

    @AfterEach
    void tearDown() {
        projectionRepository.deleteAll();
    }

    @Test
    @DisplayName("IT: GET /api/v1/settlements/{id} retorna projeção persistida no PostgreSQL")
    void shouldReturnSettlementById() throws Exception {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SettlementProjection projection = new SettlementProjection(
                messageId,
                777L,
                "ACC-REAL-1",
                "USD",
                new BigDecimal("999.99"),
                "WIRE_TRANSFER",
                null,
                now.minusMinutes(10),
                now
        );
        projectionRepository.save(projection);

        mockMvc.perform(get("/api/v1/settlements/777")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(777))
                .andExpect(jsonPath("$.settlementId").value(777))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-REAL-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("999.99"))
                .andExpect(jsonPath("$.settlementType").value("WIRE_TRANSFER"))
                .andExpect(jsonPath("$.originalSettlementId").doesNotExist())
                .andExpect(jsonPath("$.acceptedAt").exists())
                .andExpect(jsonPath("$.processedAt").exists());
    }

    @Test
    @DisplayName("IT: GET /api/v1/settlements/{id} retorna 404 para ID inexistente")
    void shouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/999999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Settlement projection with ID 999999 not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements/999999"));
    }

    @Test
    @DisplayName("IT: GET /api/v1/settlements?accountId=... retorna lista persistida no PostgreSQL ordenada")
    void shouldReturnSettlementsByAccount() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        SettlementProjection p1 = new SettlementProjection(
                UUID.randomUUID(), 101L, "ACC-QUERY-IT", "USD", new BigDecimal("100.00"), "CARD_PAYOUT", null, now.minusMinutes(5), now
        );
        SettlementProjection p2 = new SettlementProjection(
                UUID.randomUUID(), 102L, "ACC-QUERY-IT", "USD", new BigDecimal("200.00"), "CARD_PAYOUT", null, now.minusMinutes(1), now
        );
        SettlementProjection p3 = new SettlementProjection(
                UUID.randomUUID(), 103L, "ACC-OTHER", "USD", new BigDecimal("300.00"), "CARD_PAYOUT", null, now, now
        );

        projectionRepository.save(p1);
        projectionRepository.save(p2);
        projectionRepository.save(p3);

        mockMvc.perform(get("/api/v1/settlements")
                        .param("accountId", "ACC-QUERY-IT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // Ordered by acceptedAt desc: p2 (minusMinutes(1)) before p1 (minusMinutes(5))
                .andExpect(jsonPath("$[0].settlementId").value(102))
                .andExpect(jsonPath("$[0].amount").value("200.00"))
                .andExpect(jsonPath("$[1].settlementId").value(101))
                .andExpect(jsonPath("$[1].amount").value("100.00"));
    }

    @Test
    @DisplayName("IT: GET /api/v1/settlements sem accountId retorna 400 Bad Request")
    void shouldReturn400WhenMissingAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/settlements")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Query parameter 'accountId' is required"));
    }
}
