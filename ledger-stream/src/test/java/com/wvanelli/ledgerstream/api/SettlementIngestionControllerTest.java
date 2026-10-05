package com.wvanelli.ledgerstream.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.api.dto.SettlementIngestRequest;
import com.wvanelli.ledgerstream.application.IdempotencyConflictException;
import com.wvanelli.ledgerstream.application.InvalidChargebackException;
import com.wvanelli.ledgerstream.application.SettlementApplicationService;
import com.wvanelli.ledgerstream.application.SettlementRegistrationResult;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementIngestionController.class)
class SettlementIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SettlementApplicationService settlementApplicationService;

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 201 Created com Location para novo registro")
    void shouldReturn201WhenNewSettlementIsRegistered() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-CORP-1",
                "USD",
                "1500.50",
                SettlementType.CARD_PAYOUT,
                null,
                "Payout for vendor #42"
        );

        SettlementEvent mockEvent = new SettlementEvent(
                key,
                "dummy_checksum_64chars",
                "ACC-CORP-1",
                "USD",
                new MonetaryAmount("1500.50"),
                SettlementType.CARD_PAYOUT,
                "Payout for vendor #42"
        );
        mockEvent.transitionTo(SettlementStatus.COMMITTED);
        ReflectionTestUtils.setField(mockEvent, "id", 1001L);

        SettlementAttachment attachment = new SettlementAttachment("invoice.pdf", 2048L, "application/pdf", "perm/invoice.pdf");
        mockEvent.addAttachment(attachment);

        when(settlementApplicationService.registerSettlement(any()))
                .thenReturn(SettlementRegistrationResult.created(mockEvent));

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );
        MockMultipartFile attachmentPart = new MockMultipartFile(
                "attachment",
                "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "synthetic-pdf-content".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .file(attachmentPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/settlements/1001")))
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.idempotencyKey").value(key.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-CORP-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("1500.50"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.hasAttachment").value(true))
                .andExpect(jsonPath("$.attachmentFileName").value("invoice.pdf"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(settlementApplicationService).registerSettlement(any());
    }

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 200 OK sem Location para replay idempotente")
    void shouldReturn200WhenReplayingIdempotentSettlement() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-CORP-1",
                "USD",
                "1500.50",
                SettlementType.CARD_PAYOUT,
                null,
                "Payout for vendor #42"
        );

        SettlementEvent mockEvent = new SettlementEvent(
                key,
                "dummy_checksum_64chars",
                "ACC-CORP-1",
                "USD",
                new MonetaryAmount("1500.50"),
                SettlementType.CARD_PAYOUT,
                "Payout for vendor #42"
        );
        mockEvent.transitionTo(SettlementStatus.COMMITTED);
        ReflectionTestUtils.setField(mockEvent, "id", 1001L);

        when(settlementApplicationService.registerSettlement(any()))
                .thenReturn(SettlementRegistrationResult.replay(mockEvent));

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.idempotencyKey").value(key.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-CORP-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("1500.50"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.hasAttachment").value(false));

        verify(settlementApplicationService).registerSettlement(any());
    }

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 409 Conflict para conflito de checksum")
    void shouldReturn409WhenChecksumConflictOccurs() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-CORP-1",
                "USD",
                "2000.00",
                SettlementType.CARD_PAYOUT
        );

        when(settlementApplicationService.registerSettlement(any()))
                .thenThrow(new IdempotencyConflictException("Idempotency key exists but payload differs"));

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Idempotency key exists but payload differs"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 400 Bad Request para chargeback invalido")
    void shouldReturn400WhenChargebackIsInvalid() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-CORP-1",
                "USD",
                "100.00",
                SettlementType.CHARGEBACK_ADJUSTMENT,
                null,
                "Invalid chargeback"
        );

        when(settlementApplicationService.registerSettlement(any()))
                .thenThrow(new InvalidChargebackException("Chargeback adjustment must reference an original settlement."));

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Chargeback adjustment must reference an original settlement."))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 400 Bad Request quando header X-Idempotency-Key estiver ausente")
    void shouldReturn400WhenMissingIdempotencyKeyHeader() throws Exception {
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-CORP-1",
                "USD",
                "1500.50",
                SettlementType.CARD_PAYOUT
        );

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Missing required header: X-Idempotency-Key"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }

    @Test
    @DisplayName("POST /api/v1/settlements deve retornar 400 Bad Request para metadata com campos invalidos")
    void shouldReturn400WhenMetadataValidationFails() throws Exception {
        UUID key = UUID.randomUUID();
        // accountId em branco, currency invalida, amount invalido
        SettlementIngestRequest invalidRequest = new SettlementIngestRequest(
                "",
                "US",
                "invalid-amount",
                null,
                null,
                null
        );

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(invalidRequest)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }
}
