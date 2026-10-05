package com.wvanelli.ledgerstream.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.api.dto.SettlementIngestRequest;
import com.wvanelli.ledgerstream.domain.AttachmentStatus;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.repository.SettlementAttachmentRepository;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class SettlementIngestionControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementAttachmentRepository attachmentRepository;

    @Autowired
    private SettlementOutboxRepository outboxRepository;

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        attachmentRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("IT: Novo registro com attachment retorna 201 Created com Location e persiste no banco")
    void shouldRegisterNewSettlementWithAttachmentAndReturn201() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-INGEST-IT-1",
                "USD",
                "250.75",
                SettlementType.WIRE_TRANSFER,
                null,
                "IT Ingestion wire payout"
        );

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );
        String receiptContent = "synthetic receipt IT 2026-10-05";
        MockMultipartFile attachmentPart = new MockMultipartFile(
                "attachment",
                "receipt-wire.txt",
                "text/plain",
                receiptContent.getBytes(StandardCharsets.UTF_8)
        );

        var resultActions = mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .file(attachmentPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.idempotencyKey").value(key.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-INGEST-IT-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("250.75"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.hasAttachment").value(true))
                .andExpect(jsonPath("$.attachmentFileName").value("receipt-wire.txt"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        // Extract ID to verify Location header and DB persistence
        SettlementEvent saved = eventRepository.findByIdempotencyKey(key).orElseThrow();
        resultActions.andExpect(header().string("Location", endsWith("/api/v1/settlements/" + saved.getId())));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        assertThat(saved.getAmount()).isEqualByComparingTo("250.75");

        List<SettlementAttachment> attachments = attachmentRepository.findBySettlementEventId(saved.getId());
        assertThat(attachments).hasSize(1);
        SettlementAttachment att = attachments.getFirst();
        assertThat(att.getOriginalFileName()).isEqualTo("receipt-wire.txt");
        assertThat(att.getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(Path.of(att.getStoragePath())).exists();
        assertThat(Files.readString(Path.of(att.getStoragePath()))).isEqualTo(receiptContent);
    }

    @Test
    @DisplayName("IT: Replay idempotente com mesmo payload retorna 200 OK sem criar duplicata")
    void shouldReplayIdempotentSettlementAndReturn200() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-INGEST-IT-2",
                "USD",
                "100.00",
                SettlementType.CARD_PAYOUT,
                null,
                "First execution"
        );

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(requestDto)
        );

        // 1. Initial execution -> 201 Created
        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        Long firstId = eventRepository.findByIdempotencyKey(key).orElseThrow().getId();

        // 2. Replay execution with identical payload -> 200 OK
        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.idempotencyKey").value(key.toString()))
                .andExpect(jsonPath("$.accountId").value("ACC-INGEST-IT-2"))
                .andExpect(jsonPath("$.amount").value("100.00"))
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // Only 1 event exists
        assertThat(eventRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("IT: Conflito de checksum para mesma idempotency key retorna 409 Conflict")
    void shouldReturn409ConflictWhenChecksumDiffersForSameKey() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest initialRequest = new SettlementIngestRequest(
                "ACC-INGEST-IT-3",
                "USD",
                "100.00",
                SettlementType.CARD_PAYOUT,
                null,
                "Initial batch"
        );

        MockMultipartFile initialPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(initialRequest)
        );

        // Initial request -> 201 Created
        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(initialPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isCreated());

        // Conflicting request -> same key, different amount
        SettlementIngestRequest conflictingRequest = new SettlementIngestRequest(
                "ACC-INGEST-IT-3",
                "USD",
                "200.00",
                SettlementType.CARD_PAYOUT,
                null,
                "Different amount conflict"
        );

        MockMultipartFile conflictingPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(conflictingRequest)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(conflictingPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Idempotency key exists but payload differs"))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }

    @Test
    @DisplayName("IT: Chargeback invalido apontando para settlement inexistente retorna 400 Bad Request")
    void shouldReturn400BadRequestForInvalidChargeback() throws Exception {
        UUID key = UUID.randomUUID();
        SettlementIngestRequest invalidChargeback = new SettlementIngestRequest(
                "ACC-INGEST-IT-4",
                "USD",
                "50.00",
                SettlementType.CHARGEBACK_ADJUSTMENT,
                999999L,
                "Chargeback pointing to non-existent ID"
        );

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(invalidChargeback)
        );

        mockMvc.perform(multipart("/api/v1/settlements")
                        .file(metadataPart)
                        .header("X-Idempotency-Key", key.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Original settlement not found."))
                .andExpect(jsonPath("$.path").value("/api/v1/settlements"));
    }

    @Test
    @DisplayName("IT: Requisicao sem header X-Idempotency-Key retorna 400 Bad Request")
    void shouldReturn400BadRequestWhenMissingIdempotencyKey() throws Exception {
        SettlementIngestRequest requestDto = new SettlementIngestRequest(
                "ACC-INGEST-IT-5",
                "USD",
                "75.00",
                SettlementType.CARD_PAYOUT,
                null,
                "Missing key"
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
}
