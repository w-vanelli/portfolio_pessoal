package com.wvanelli.ledgerstream.repository;

import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SettlementAttachmentRepository} against a real PostgreSQL 16 instance.
 *
 * <p>Validates FK constraints, cascade behavior at the database level, and custom query methods.
 */
@Transactional
@Rollback
class SettlementAttachmentRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementAttachmentRepository attachmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("findBySettlementEventId should return attachments for a given event")
    void shouldFindAttachmentsBySettlementEventId() {
        SettlementEvent event = createEventWithAttachments(2);
        eventRepository.saveAndFlush(event);
        entityManager.clear();

        List<SettlementAttachment> attachments =
                attachmentRepository.findBySettlementEventId(event.getId());

        assertThat(attachments).hasSize(2);
        assertThat(attachments)
                .extracting(SettlementAttachment::getOriginalFileName)
                .containsExactlyInAnyOrder("synth-doc-0.pdf", "synth-doc-1.pdf");
    }

    @Test
    @DisplayName("findBySettlementEventId should return empty list for event without attachments")
    void shouldReturnEmptyForEventWithoutAttachments() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(),
                "checksum-no-attach",
                "ACC-NOATTACH-01",
                "USD",
                new MonetaryAmount("250.00"),
                SettlementType.INVOICE_SETTLEMENT,
                "Event without attachments"
        );
        eventRepository.saveAndFlush(event);
        entityManager.clear();

        List<SettlementAttachment> result =
                attachmentRepository.findBySettlementEventId(event.getId());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Attachment FK should reference existing settlement event")
    void shouldPersistAttachmentWithValidFK() {
        SettlementEvent event = createEventWithAttachments(1);
        eventRepository.saveAndFlush(event);
        entityManager.clear();

        SettlementAttachment loaded = attachmentRepository
                .findBySettlementEventId(event.getId())
                .get(0);

        // Verify the FK relationship is materialized in the database
        assertThat(loaded.getSettlementEvent()).isNotNull();
        assertThat(loaded.getSettlementEvent().getId()).isEqualTo(event.getId());
    }

    @Test
    @DisplayName("Database cascade should delete attachments when event is deleted via native SQL")
    void shouldCascadeDeleteAttachmentsViaDatabase() {
        SettlementEvent event = createEventWithAttachments(3);
        eventRepository.saveAndFlush(event);
        Long eventId = event.getId();
        entityManager.clear();

        // Verify attachments exist
        assertThat(attachmentRepository.findBySettlementEventId(eventId)).hasSize(3);

        // Delete event via native SQL — bypasses JPA cascade
        entityManager.createNativeQuery("DELETE FROM settlement_events WHERE id = :id")
                .setParameter("id", eventId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Database-level ON DELETE CASCADE should have removed all attachments
        assertThat(attachmentRepository.findBySettlementEventId(eventId)).isEmpty();
    }

    // --- Helpers ---

    private SettlementEvent createEventWithAttachments(int attachmentCount) {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(),
                "synth-checksum-" + UUID.randomUUID().toString().substring(0, 8),
                "ACC-ATTACH-" + UUID.randomUUID().toString().substring(0, 4),
                "BRL",
                new MonetaryAmount("1200.00"),
                SettlementType.WIRE_TRANSFER,
                "Synthetic event with attachments"
        );

        for (int i = 0; i < attachmentCount; i++) {
            event.addAttachment(new SettlementAttachment(
                    "synth-doc-" + i + ".pdf",
                    (long) ((i + 1) * 1024),
                    "application/pdf",
                    "/storage/staging/synth-" + UUID.randomUUID().toString().substring(0, 8) + ".tmp"
            ));
        }

        return event;
    }
}
