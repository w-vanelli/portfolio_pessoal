package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.api.dto.SettlementProjectionResponse;
import com.wvanelli.ledgerstream.application.SettlementProjectionQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementQueryController.class)
class SettlementQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementProjectionQueryService queryService;

    @Test
    @DisplayName("GET /api/v1/settlements/{id} deve retornar 200 quando settlement existir")
    void getByIdShouldReturn200WhenFound() throws Exception {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime acceptedAt = OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime processedAt = OffsetDateTime.of(2026, 9, 26, 12, 0, 1, 0, ZoneOffset.UTC);

        SettlementProjectionResponse response = new SettlementProjectionResponse(
                1001L,
                1001L,
                messageId,
                "ACC-CORP-1",
                "USD",
                "1500.50",
                "WIRE_TRANSFER",
                null,
                acceptedAt,
                processedAt
        );

        when(queryService.findById(1001L)).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/v1/settlements/1001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.settlementId").value(1001))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-CORP-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("1500.50"))
                .andExpect(jsonPath("$.settlementType").value("WIRE_TRANSFER"))
                .andExpect(jsonPath("$.originalSettlementId").doesNotExist())
                .andExpect(jsonPath("$.acceptedAt").exists())
                .andExpect(jsonPath("$.processedAt").exists());

        verify(queryService).findById(1001L);
    }

    @Test
    @DisplayName("GET /api/v1/settlements/{id} deve retornar 404 quando settlement nao existir")
    void getByIdShouldReturn404WhenNotFound() throws Exception {
        when(queryService.findById(9999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/settlements/9999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Settlement projection with ID 9999 not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements/9999"));

        verify(queryService).findById(9999L);
    }

    @Test
    @DisplayName("GET /api/v1/settlements/{id} deve retornar 400 para ID invalido")
    void getByIdShouldReturn400ForInvalidId() throws Exception {
        // ID negativo
        mockMvc.perform(get("/api/v1/settlements/-10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // ID nao numerico
        mockMvc.perform(get("/api/v1/settlements/invalid-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Parameter 'id' must be of type Long"));
    }

    @Test
    @DisplayName("GET /api/v1/settlements?accountId=... deve retornar 200 com array de projecoes")
    void getByAccountIdShouldReturn200WithList() throws Exception {
        UUID messageId = UUID.randomUUID();
        SettlementProjectionResponse item = new SettlementProjectionResponse(
                501L,
                501L,
                messageId,
                "ACC-TEST-9",
                "EUR",
                "350.00",
                "CARD_PAYOUT",
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        when(queryService.findByAccountId("ACC-TEST-9")).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/settlements")
                        .param("accountId", "ACC-TEST-9")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(501))
                .andExpect(jsonPath("$[0].accountId").value("ACC-TEST-9"))
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].amount").value("350.00"));

        verify(queryService).findByAccountId("ACC-TEST-9");
    }

    @Test
    @DisplayName("GET /api/v1/settlements sem accountId deve retornar 400 Bad Request")
    void getByAccountIdShouldReturn400WhenMissingParameter() throws Exception {
        mockMvc.perform(get("/api/v1/settlements")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Query parameter 'accountId' is required"));
    }

    @Test
    @DisplayName("GET /api/v1/settlements/idempotency/{key} deve retornar 200 quando projecao existir")
    void getByIdempotencyKeyShouldReturn200WhenFound() throws Exception {
        UUID key = UUID.randomUUID();
        OffsetDateTime acceptedAt = OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime processedAt = OffsetDateTime.of(2026, 9, 26, 12, 0, 1, 0, ZoneOffset.UTC);

        SettlementProjectionResponse response = new SettlementProjectionResponse(
                2001L,
                2001L,
                key,
                "ACC-CORP-2",
                "BRL",
                "9999.99",
                "INVOICE_SETTLEMENT",
                null,
                acceptedAt,
                processedAt
        );

        when(queryService.findByIdempotencyKey(key)).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/v1/settlements/idempotency/" + key)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2001))
                .andExpect(jsonPath("$.settlementId").value(2001))
                .andExpect(jsonPath("$.messageId").value(key.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-CORP-2"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.amount").value("9999.99"))
                .andExpect(jsonPath("$.settlementType").value("INVOICE_SETTLEMENT"))
                .andExpect(jsonPath("$.originalSettlementId").doesNotExist())
                .andExpect(jsonPath("$.acceptedAt").exists())
                .andExpect(jsonPath("$.processedAt").exists());

        verify(queryService).findByIdempotencyKey(key);
    }

    @Test
    @DisplayName("GET /api/v1/settlements/idempotency/{key} deve retornar 404 quando projecao nao existir")
    void getByIdempotencyKeyShouldReturn404WhenNotFound() throws Exception {
        UUID key = UUID.randomUUID();

        when(queryService.findByIdempotencyKey(key)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/settlements/idempotency/" + key)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(
                        "Settlement projection with idempotency key " + key + " not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements/idempotency/" + key));

        verify(queryService).findByIdempotencyKey(key);
    }
}
