package com.wvanelli.ledgerstream.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.api.dto.SettlementIngestRequest;
import com.wvanelli.ledgerstream.application.SettlementAuditQueryService;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAuditLog;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.repository.SettlementAuditLogRepository;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SettlementAuditIT extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SettlementEventRepository events;
    @Autowired private SettlementAuditLogRepository logs;
    @Autowired private SettlementOutboxRepository outbox;
    @Autowired private SettlementAuditQueryService query;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        outbox.deleteAll();
        events.deleteAll();
    }

    @Test
    void restIngestionShouldExposePersistedHistoryAndReplayWithoutDuplicates() throws Exception {
        UUID key = UUID.randomUUID();
        Long id = ingest(key, 201);
        var before = query.findBySettlementId(id);
        assertThat(before).hasSize(2);
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].settlementId").value(id))
                .andExpect(jsonPath("$[0].previousStatus").value(nullValue()))
                .andExpect(jsonPath("$[0].newStatus").value("STAGED"))
                .andExpect(jsonPath("$[1].previousStatus").value("STAGED"))
                .andExpect(jsonPath("$[1].newStatus").value("COMMITTED"))
                .andExpect(jsonPath("$[1].eventDetails").value(nullValue()));
        assertThat(ingest(key, 200)).isEqualTo(id);
        assertThat(query.findBySettlementId(id)).containsExactlyElementsOf(before);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void transitionsShouldCascadeAndRemainIsolatedAcrossSettlements() throws Exception {
        Long id = ingest(UUID.randomUUID(), 201);
        Long otherId = ingest(UUID.randomUUID(), 201);
        var otherHistory = query.findBySettlementId(otherId);
        transaction().executeWithoutResult(tx -> {
            var event = events.findLockedById(id).orElseThrow();
            event.transitionTo(SettlementStatus.DISPATCHED, "Broker confirmed");
            events.flush();
        });
        var history = query.findBySettlementId(id);
        assertThat(history).hasSize(3).allSatisfy(entry -> assertThat(entry.settlementId()).isEqualTo(id));
        assertThat(history.getLast().previousStatus()).isEqualTo(SettlementStatus.COMMITTED);
        assertThat(history.getLast().newStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        assertThat(history.getLast().eventDetails()).isEqualTo("Broker confirmed");
        assertThat(query.findBySettlementId(otherId)).containsExactlyElementsOf(otherHistory);
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].eventDetails").value("Broker confirmed"));
    }

    @Test
    void rollbackAfterFlushShouldUndoBothStatusAndAudit() throws Exception {
        Long id = ingest(UUID.randomUUID(), 201);
        var before = query.findBySettlementId(id);
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
            events.findLockedById(id).orElseThrow().transitionTo(SettlementStatus.DISPATCHED);
            events.flush();
            assertThat(logs.findBySettlementEventIdOrderByCreatedAtAsc(id)).hasSize(3);
            assertThat(jdbc.queryForObject("select status from settlement_events where id = ?", String.class, id))
                    .isEqualTo("DISPATCHED");
            throw new IllegalStateException("Failure after flush");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Failure after flush");
        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        assertThat(query.findBySettlementId(id)).containsExactlyElementsOf(before);
    }

    @Test
    void auditInsertFailureShouldRollbackStatus() throws Exception {
        Long id = ingest(UUID.randomUUID(), 201);
        var before = query.findBySettlementId(id);
        // PostgreSQL TEXT rejects NUL: exercise a real audit write failure.
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
            events.findLockedById(id).orElseThrow().transitionTo(SettlementStatus.DISPATCHED, "invalid\u0000detail");
            events.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        assertThat(query.findBySettlementId(id)).containsExactlyElementsOf(before);
    }

    @Test
    void equalTimestampsShouldBeOrderedByIdInRepositoryAggregateAndRest() throws Exception {
        Long id = ingest(UUID.randomUUID(), 201);
        transaction().executeWithoutResult(tx -> events.findLockedById(id).orElseThrow()
                .transitionTo(SettlementStatus.DISPATCHED));
        // Rewrite in reverse ID order so physical row order cannot provide the tie break.
        var ids = logs.findBySettlementEventIdOrderByCreatedAtAsc(id).stream()
                .map(SettlementAuditLog::getId).sorted().toList();
        for (Long logId : ids.reversed()) {
            jdbc.update("update settlement_audit_log set created_at = '2026-10-07T12:00:00Z' where id = ?", logId);
        }
        assertThat(logs.findBySettlementEventIdOrderByCreatedAtAsc(id))
                .extracting(SettlementAuditLog::getId).containsExactlyElementsOf(ids);
        transaction().executeWithoutResult(tx -> assertThat(events.findById(id).orElseThrow().getAuditLogs())
                .extracting(SettlementAuditLog::getId).containsExactlyElementsOf(ids));
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$[1].id").value(ids.get(1)))
                .andExpect(jsonPath("$[2].id").value(ids.get(2)));
    }

    @Test
    void legacySettlementAndMissingSettlementShouldReturn404WithoutInventingHistory() throws Exception {
        Long id = jdbc.queryForObject("""
                insert into settlement_events
                    (idempotency_key, payload_checksum, account_id, currency, amount, settlement_type, status)
                values (?, 'legacy', 'ACC-LEGACY', 'USD', 10, 'CARD_PAYOUT', 'COMMITTED') returning id
                """, Long.class, UUID.randomUUID());
        transaction().executeWithoutResult(tx -> assertThat(events.findById(id).orElseThrow().getAuditLogs()).isEmpty());
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/settlements/{id}/audit", Long.MAX_VALUE)).andExpect(status().isNotFound());
        transaction().executeWithoutResult(tx -> events.findLockedById(id).orElseThrow()
                .transitionTo(SettlementStatus.DISPATCHED));
        assertThat(query.findBySettlementId(id)).singleElement().satisfies(entry -> {
            assertThat(entry.previousStatus()).isEqualTo(SettlementStatus.COMMITTED);
            assertThat(entry.newStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        });
    }

    @Test
    void failedCreationShouldLeaveNeitherEventNorInitialAudit() {
        var event = new SettlementEvent(UUID.randomUUID(), "checksum", "ACC-ROLLBACK", "USD",
                new MonetaryAmount("10"), SettlementType.CARD_PAYOUT, null);
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
            events.saveAndFlush(event);
            assertThat(logs.findBySettlementEventIdOrderByCreatedAtAsc(event.getId())).hasSize(1);
            throw new IllegalStateException("Rollback creation");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Rollback creation");
        assertThat(events.findById(event.getId())).isEmpty();
        assertThat(logs.findBySettlementEventIdOrderByCreatedAtAsc(event.getId())).isEmpty();
    }

    @Test
    void v8ShouldUpgradeV7PreservingExistingAuditAndForeignKey() {
        String schema = "audit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("7").load().migrate();
            Long id = jdbc.queryForObject("insert into " + schema + ".settlement_events "
                    + "(idempotency_key, payload_checksum, account_id, currency, amount, settlement_type, status) "
                    + "values (?, 'old', 'ACC-OLD', 'USD', 10, 'CARD_PAYOUT', 'STAGED') returning id",
                    Long.class, UUID.randomUUID());
            Long logId = jdbc.queryForObject("insert into " + schema + ".settlement_audit_log "
                    + "(settlement_id, previous_status, new_status, event_details, created_at) "
                    + "values (?, null, 'STAGED', 'Existing history', '2026-10-01T12:00:00Z') returning id", Long.class, id);
            Long legacyId = jdbc.queryForObject("insert into " + schema + ".settlement_events "
                    + "(idempotency_key, payload_checksum, account_id, currency, amount, settlement_type, status) "
                    + "values (?, 'old', 'ACC-OLD', 'USD', 10, 'CARD_PAYOUT', 'COMMITTED') returning id",
                    Long.class, UUID.randomUUID());
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForMap("select id, settlement_event_id, previous_status, new_status, event_details "
                    + "from " + schema + ".settlement_audit_log"))
                    .containsEntry("id", logId).containsEntry("settlement_event_id", id)
                    .containsEntry("previous_status", null).containsEntry("new_status", "STAGED")
                    .containsEntry("event_details", "Existing history");
            assertThat(jdbc.queryForObject("select count(*) from " + schema
                    + ".settlement_audit_log where settlement_event_id = ?", Long.class, legacyId)).isZero();
            assertThat(jdbc.queryForObject("select created_at from " + schema + ".settlement_audit_log",
                    java.sql.Timestamp.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-01T12:00:00Z"));
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = ? "
                    + "and indexname = 'idx_audit_event_created_id'", String.class, schema))
                    .contains("(settlement_event_id, created_at, id)");
            assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".settlement_audit_log "
                    + "(settlement_event_id, new_status) values (?, 'STAGED')", Long.MAX_VALUE))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".settlement_audit_log "
                    + "(settlement_event_id, new_status) values (?, null)", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Long nextLogId = jdbc.queryForObject("insert into " + schema + ".settlement_audit_log "
                    + "(settlement_event_id, previous_status, new_status) values (?, 'STAGED', 'COMMITTED') returning id",
                    Long.class, id);
            assertThat(nextLogId).isGreaterThan(logId);
            jdbc.update("delete from " + schema + ".settlement_events where id = ?", id);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".settlement_audit_log", Long.class)).isZero();
        } finally {
            // Only the uniquely named test schema, never the application's public schema.
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Long ingest(UUID key, int expectedStatus) throws Exception {
        var request = new SettlementIngestRequest("ACC-AUDIT", "USD", "10.00",
                SettlementType.CARD_PAYOUT, null, "Synthetic metadata, not audit details");
        var metadata = new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(request));
        var result = mockMvc.perform(multipart("/api/v1/settlements").file(metadata)
                        .header("X-Idempotency-Key", key))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").longValue();
    }
}
