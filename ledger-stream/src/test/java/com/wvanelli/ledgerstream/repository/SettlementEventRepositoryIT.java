package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.domain.AttachmentStatus;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link SettlementEventRepository} against a real PostgreSQL 16 instance.
 *
 * <p>Validates:
 * <ul>
 *   <li>Flyway migrations execute successfully (schema created by V1 + V2)</li>
 *   <li>JPA mappings align with the Flyway-created schema (Hibernate validate mode)</li>
 *   <li>Persistence and retrieval of monetary values, enums, timestamps, and UUIDs</li>
 *   <li>Idempotency key uniqueness constraint at the database level</li>
 *   <li>CHECK constraint rejecting zero and negative amounts at the database level</li>
 *   <li>Cascade behavior defined by the database (not just JPA)</li>
 *   <li>Custom query methods</li>
 * </ul>
 */
@Transactional
@Rollback
class SettlementEventRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementAttachmentRepository attachmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Flyway migrations should create schema successfully and Hibernate should validate mappings")
    void flywayMigrationsShouldCreateSchema() {
        // If this test runs without errors, Flyway V1 + V2 executed successfully
        // and Hibernate validate mode confirmed JPA mappings match the schema
        long count = eventRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should persist and read back all field types accurately")
    void shouldPersistAndReadAllFieldTypes() {
        UUID idempotencyKey = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        MonetaryAmount amount = new MonetaryAmount("7500.50");

        SettlementEvent event = new SettlementEvent(
                idempotencyKey,
                "a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890",
                "ACC-SYNTH-001",
                "USD",
                amount,
                SettlementType.WIRE_TRANSFER,
                "Synthetic test settlement for integration validation"
        );

        eventRepository.saveAndFlush(event);
        entityManager.clear(); // Force read from database, not L1 cache

        SettlementEvent loaded = eventRepository.findById(event.getId()).orElseThrow();

        // Monetary value: BigDecimal precision preserved
        assertThat(loaded.getAmount()).isEqualByComparingTo(new BigDecimal("7500.50"));
        assertThat(loaded.getMonetaryAmount().toCanonicalString()).isEqualTo("7500.50");

        // Enums: persisted as strings, read back correctly
        assertThat(loaded.getStatus()).isEqualTo(SettlementStatus.STAGED);
        assertThat(loaded.getSettlementType()).isEqualTo(SettlementType.WIRE_TRANSFER);

        // UUID: preserved exactly
        assertThat(loaded.getIdempotencyKey()).isEqualTo(idempotencyKey);

        // Timestamps: not null and have timezone information
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getCreatedAt()).isInstanceOf(OffsetDateTime.class);

        // String fields
        assertThat(loaded.getAccountId()).isEqualTo("ACC-SYNTH-001");
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getPayloadChecksum())
                .isEqualTo("a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890");
        assertThat(loaded.getDescription()).isEqualTo("Synthetic test settlement for integration validation");

        // Chargeback reference: null for non-chargeback events
        assertThat(loaded.getOriginalSettlementId()).isNull();
    }

    @Test
    @DisplayName("Should persist event with attachment via JPA cascade and verify relationship")
    void shouldPersistEventWithAttachmentViaCascade() {
        SettlementEvent event = createSyntheticEvent("ACC-CASCADE-01", "EUR", "3200.00");

        SettlementAttachment attachment = new SettlementAttachment(
                "synthetic-receipt-2026.pdf",
                4096L,
                "application/pdf",
                "/storage/staging/synth-file-001.tmp"
        );
        event.addAttachment(attachment);

        eventRepository.saveAndFlush(event);
        entityManager.clear();

        SettlementEvent loaded = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(loaded.getAttachments()).hasSize(1);

        SettlementAttachment loadedAttachment = loaded.getAttachments().get(0);
        assertThat(loadedAttachment.getOriginalFileName()).isEqualTo("synthetic-receipt-2026.pdf");
        assertThat(loadedAttachment.getFileSizeBytes()).isEqualTo(4096L);
        assertThat(loadedAttachment.getContentType()).isEqualTo("application/pdf");
        assertThat(loadedAttachment.getStatus()).isEqualTo(AttachmentStatus.STAGED);
        assertThat(loadedAttachment.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should enforce database cascade delete — deleting event removes attachments")
    void shouldEnforceDatabaseCascadeDelete() {
        SettlementEvent event = createSyntheticEvent("ACC-DBCASCADE-01", "BRL", "1500.00");
        event.addAttachment(new SettlementAttachment(
                "cascade-test.pdf", 2048L, "application/pdf", "/storage/staging/cascade-001.tmp"
        ));

        eventRepository.saveAndFlush(event);
        Long eventId = event.getId();
        Long attachmentId = event.getAttachments().get(0).getId();
        entityManager.clear();

        // Verify attachment exists
        assertThat(attachmentRepository.findById(attachmentId)).isPresent();

        // Delete via native SQL to bypass JPA cascade — proves database-level cascade
        entityManager.createNativeQuery("DELETE FROM settlement_events WHERE id = :id")
                .setParameter("id", eventId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Attachment should be gone due to ON DELETE CASCADE in the database
        assertThat(attachmentRepository.findById(attachmentId)).isEmpty();
    }

    @Test
    @DisplayName("Should enforce idempotency key uniqueness at database level")
    void shouldEnforceIdempotencyKeyUniqueness() {
        UUID sharedKey = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef");

        SettlementEvent first = new SettlementEvent(
                sharedKey, "checksum-first", "ACC-UNIQUE-01", "USD",
                new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "First event"
        );
        eventRepository.saveAndFlush(first);

        SettlementEvent duplicate = new SettlementEvent(
                sharedKey, "checksum-second", "ACC-UNIQUE-01", "USD",
                new MonetaryAmount("200.00"), SettlementType.CARD_PAYOUT, "Duplicate key"
        );

        // Force SQL execution — existsByIdempotencyKey is NOT a concurrency guard
        assertThatThrownBy(() -> {
            eventRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should reject zero amount via database CHECK constraint")
    void shouldRejectZeroAmountViaDbConstraint() {
        // Bypass domain validation by using native SQL to test the CHECK constraint directly
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "INSERT INTO settlement_events " +
                    "(idempotency_key, payload_checksum, account_id, currency, amount, " +
                    "settlement_type, status, created_at, updated_at) " +
                    "VALUES (:key, :checksum, :account, :currency, :amount, " +
                    ":type, :status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            )
            .setParameter("key", UUID.randomUUID())
            .setParameter("checksum", "zero-test-checksum")
            .setParameter("account", "ACC-ZERO-TEST")
            .setParameter("currency", "USD")
            .setParameter("amount", BigDecimal.ZERO)
            .setParameter("type", "CARD_PAYOUT")
            .setParameter("status", "STAGED")
            .executeUpdate();
            entityManager.flush();
        }).satisfiesAnyOf(
                e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class),
                e -> assertThat(e).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class),
                e -> assertThat(e).hasCauseInstanceOf(org.postgresql.util.PSQLException.class)
        );
    }

    @Test
    @DisplayName("Should reject negative amount via database CHECK constraint")
    void shouldRejectNegativeAmountViaDbConstraint() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "INSERT INTO settlement_events " +
                    "(idempotency_key, payload_checksum, account_id, currency, amount, " +
                    "settlement_type, status, created_at, updated_at) " +
                    "VALUES (:key, :checksum, :account, :currency, :amount, " +
                    ":type, :status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            )
            .setParameter("key", UUID.randomUUID())
            .setParameter("checksum", "negative-test-checksum")
            .setParameter("account", "ACC-NEG-TEST")
            .setParameter("currency", "USD")
            .setParameter("amount", new BigDecimal("-50.00"))
            .setParameter("type", "WIRE_TRANSFER")
            .setParameter("status", "STAGED")
            .executeUpdate();
            entityManager.flush();
        }).satisfiesAnyOf(
                e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class),
                e -> assertThat(e).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class),
                e -> assertThat(e).hasCauseInstanceOf(org.postgresql.util.PSQLException.class)
        );
    }

    @Test
    @DisplayName("findByIdempotencyKey should return matching event")
    void shouldFindByIdempotencyKey() {
        UUID key = UUID.randomUUID();
        SettlementEvent event = new SettlementEvent(
                key, "checksum-find", "ACC-FIND-01", "GBP",
                new MonetaryAmount("850.25"), SettlementType.INVOICE_SETTLEMENT, "Find test"
        );
        eventRepository.saveAndFlush(event);
        entityManager.clear();

        Optional<SettlementEvent> found = eventRepository.findByIdempotencyKey(key);
        assertThat(found).isPresent();
        assertThat(found.get().getAccountId()).isEqualTo("ACC-FIND-01");
    }

    @Test
    @DisplayName("existsByIdempotencyKey should return true for existing key")
    void shouldCheckExistsByIdempotencyKey() {
        UUID key = UUID.randomUUID();
        eventRepository.saveAndFlush(createSyntheticEvent("ACC-EXISTS-01", "USD", "100.00", key));
        entityManager.clear();

        assertThat(eventRepository.existsByIdempotencyKey(key)).isTrue();
        assertThat(eventRepository.existsByIdempotencyKey(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("findByAccountIdAndStatus should filter correctly")
    void shouldFindByAccountIdAndStatus() {
        String accountId = "ACC-FILTER-" + UUID.randomUUID().toString().substring(0, 8);

        SettlementEvent staged = createSyntheticEvent(accountId, "USD", "100.00");
        eventRepository.saveAndFlush(staged);

        SettlementEvent committed = createSyntheticEvent(accountId, "USD", "200.00");
        committed.transitionTo(SettlementStatus.COMMITTED);
        eventRepository.saveAndFlush(committed);

        entityManager.clear();

        List<SettlementEvent> stagedEvents = eventRepository.findByAccountIdAndStatus(
                accountId, SettlementStatus.STAGED);
        assertThat(stagedEvents).hasSize(1);
        assertThat(stagedEvents.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));

        List<SettlementEvent> committedEvents = eventRepository.findByAccountIdAndStatus(
                accountId, SettlementStatus.COMMITTED);
        assertThat(committedEvents).hasSize(1);
        assertThat(committedEvents.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("Should persist monetary boundary values correctly")
    void shouldPersistMonetaryBoundaryValues() {
        // Minimum: 0.01
        SettlementEvent minEvent = createSyntheticEvent("ACC-BOUNDARY-MIN", "USD", "0.01");
        eventRepository.saveAndFlush(minEvent);
        entityManager.clear();

        SettlementEvent loadedMin = eventRepository.findById(minEvent.getId()).orElseThrow();
        assertThat(loadedMin.getAmount()).isEqualByComparingTo(new BigDecimal("0.01"));

        // Maximum: 9999999999999.99
        SettlementEvent maxEvent = createSyntheticEvent("ACC-BOUNDARY-MAX", "USD", "9999999999999.99");
        eventRepository.saveAndFlush(maxEvent);
        entityManager.clear();

        SettlementEvent loadedMax = eventRepository.findById(maxEvent.getId()).orElseThrow();
        assertThat(loadedMax.getAmount()).isEqualByComparingTo(new BigDecimal("9999999999999.99"));
    }

    // --- Helpers ---

    private SettlementEvent createSyntheticEvent(String accountId, String currency, String amount) {
        return createSyntheticEvent(accountId, currency, amount, UUID.randomUUID());
    }

    private SettlementEvent createSyntheticEvent(String accountId, String currency, String amount, UUID key) {
        return new SettlementEvent(
                key,
                "synth-checksum-" + UUID.randomUUID().toString().substring(0, 16),
                accountId,
                currency,
                new MonetaryAmount(amount),
                SettlementType.CARD_PAYOUT,
                "Synthetic test event"
        );
    }
}
