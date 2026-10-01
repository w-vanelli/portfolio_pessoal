package com.wvanelli.ledgerstream.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.*;
import com.wvanelli.ledgerstream.storage.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettlementReconciliationServiceTest {
    @TempDir Path root;
    SettlementEventRepository events;
    SettlementAttachmentRepository attachments;
    SettlementOutboxRepository outbox;
    StagingStorageService storage;
    SettlementReconciliationService service;
    SimpleMeterRegistry registry;
    ReconciliationProperties properties;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Map<Long, SettlementEvent> eventRows = new LinkedHashMap<>();
    List<SettlementAttachment> attachmentRows = new ArrayList<>();
    Map<Long, SettlementOutboxEntry> outboxRows = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        events = mock(SettlementEventRepository.class);
        attachments = mock(SettlementAttachmentRepository.class);
        outbox = mock(SettlementOutboxRepository.class);
        storage = spy(new StagingStorageServiceImpl(root.resolve("staging").toString(), root.resolve("permanent").toString()));
        properties = new ReconciliationProperties();
        registry = new SimpleMeterRegistry();
        var tm = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) {
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        service = new SettlementReconciliationService(events, attachments, outbox, storage, tm, mapper, properties, registry);
        when(events.findLockedById(anyLong())).thenAnswer(i -> Optional.ofNullable(eventRows.get(i.getArgument(0))));
        when(attachments.findByStatus(AttachmentStatus.STAGED)).thenAnswer(i -> attachmentRows.stream()
                .filter(a -> a.getStatus() == AttachmentStatus.STAGED).toList());
        when(attachments.findBySettlementEventId(anyLong())).thenAnswer(i -> attachmentRows.stream()
                .filter(a -> a.getSettlementEvent().getId().equals(i.getArgument(0))).toList());
        when(attachments.existsByStoragePath(anyString())).thenAnswer(i -> attachmentRows.stream()
                .anyMatch(a -> a.getStoragePath().equals(i.getArgument(0))));
        when(outbox.findLockedBySettlementId(anyLong())).thenAnswer(i -> Optional.ofNullable(outboxRows.get(i.getArgument(0))));
        when(outbox.findBySettlementId(anyLong())).thenAnswer(i -> Optional.ofNullable(outboxRows.get(i.getArgument(0))));
        when(outbox.saveAndFlush(any())).thenAnswer(i -> {
            SettlementOutboxEntry entry = i.getArgument(0);
            outboxRows.put(entry.getSettlementId(), entry); return entry;
        });
        when(events.findEventsWithoutOutboxEntry(SettlementStatus.COMMITTED)).thenAnswer(i -> eventRows.values().stream()
                .filter(e -> e.getStatus() == SettlementStatus.COMMITTED && !outboxRows.containsKey(e.getId())).toList());
        when(events.findReconciliationIds()).thenAnswer(i -> new ArrayList<>(eventRows.keySet()));
    }

    SettlementEvent event(SettlementStatus status) {
        var event = new SettlementEvent(UUID.randomUUID(), "a".repeat(64), "SYNTH", "USD",
                new MonetaryAmount("12.34"), SettlementType.CARD_PAYOUT, "synthetic");
        ReflectionTestUtils.setField(event, "id", (long) eventRows.size() + 1);
        if (status == SettlementStatus.DISPATCHED) event.transitionTo(SettlementStatus.COMMITTED);
        event.transitionTo(status);
        eventRows.put(event.getId(), event);
        return event;
    }

    StagingTicket ticket() throws IOException {
        return storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("proof".getBytes()));
    }

    SettlementAttachment attachment(SettlementEvent event) throws IOException {
        var ticket = ticket();
        var a = new SettlementAttachment(ticket.originalFileName(), ticket.fileSizeBytes(), ticket.contentType(), ticket.storagePath());
        ReflectionTestUtils.setField(a, "id", (long) attachmentRows.size() + 1);
        event.addAttachment(a); attachmentRows.add(a); return a;
    }

    SettlementOutboxEntry entry(SettlementEvent event) {
        var entry = new SettlementOutboxEntry(event, "{\"immutable\":true}");
        outboxRows.put(event.getId(), entry); return entry;
    }

    @Test void removesUnreferencedContentAndEmptyDirectoriesIdempotently() throws Exception {
        Path content = Path.of(ticket().storagePath());
        Path empty = Path.of(ticket().storagePath()); Files.delete(empty);
        Thread.sleep(50); // Allow the real filesystem timestamp to precede the zero-grace cutoff.
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isEqualTo(2);
        assertThat(content.getParent()).doesNotExist(); assertThat(empty.getParent()).doesNotExist();
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isZero();
    }

    @Test void preservesReferencedAndRecentAttemptsIncludingEmptyOnes() throws Exception {
        var a = attachment(event(SettlementStatus.COMMITTED));
        Path recent = Path.of(ticket().storagePath());
        Path empty = Path.of(ticket().storagePath()); Files.delete(empty);
        Thread.sleep(50); // Allow the real filesystem timestamp to precede the zero-grace cutoff.
        assertThat(service.reconcileOrphanStagingFiles(Duration.ofMinutes(15))).isZero();
        assertThat(recent).exists(); assertThat(empty.getParent()).exists();
        service.reconcileOrphanStagingFiles(Duration.ZERO);
        assertThat(Path.of(a.getStoragePath())).exists();
    }

    @Test void usesBothFileAndDirectoryAgeAndConservativeCreationTime() throws Exception {
        Path content = Path.of(ticket().storagePath());
        Files.setLastModifiedTime(content, FileTime.from(Instant.EPOCH));
        Files.setLastModifiedTime(content.getParent(), FileTime.from(Instant.EPOCH));
        assertThat(service.reconcileOrphanStagingFiles(Duration.ofMinutes(15))).isZero();
        Files.setLastModifiedTime(content, FileTime.from(Instant.now().plusSeconds(60)));
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isZero();
        Files.setLastModifiedTime(content, FileTime.from(Instant.EPOCH));
        Files.setLastModifiedTime(content.getParent(), FileTime.from(Instant.now().plusSeconds(60)));
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isZero();
        assertThat(content).exists();
    }

    @Test void dbFailureNeverMeansOrphanAndDoesNotBlockOtherAttempts() throws Exception {
        Path retained = Path.of(ticket().storagePath()); Path removed = Path.of(ticket().storagePath());
        when(attachments.existsByStoragePath(retained.toString())).thenThrow(new IllegalStateException("DB offline"));
        Thread.sleep(50); // Allow the real filesystem timestamp to precede the zero-grace cutoff.
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isEqualTo(1);
        assertThat(retained).exists(); assertThat(removed).doesNotExist();
    }

    @Test void orphanIoFailureIsIsolatedAndRecoverable() throws Exception {
        Path retained = Path.of(ticket().storagePath()); Path removed = Path.of(ticket().storagePath());
        doThrow(new IOException("locked")).doCallRealMethod().when(storage).cleanupOrphanStagingDirectory(retained.getParent());
        Thread.sleep(50); // Allow the real filesystem timestamp to precede the zero-grace cutoff.
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isEqualTo(1);
        assertThat(retained).exists(); assertThat(removed).doesNotExist();
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isEqualTo(1);
    }

    @Test void promotesStagingAndUnblocksWithoutSpendingAttempts() throws Exception {
        var event = event(SettlementStatus.COMMITTED); var a = attachment(event); var entry = entry(event);
        entry.recordFailure("previous broker failure", OffsetDateTime.now().plusDays(1));
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(Path.of(a.getStoragePath())).hasContent("proof");
        assertThat(entry.getAvailableAt()).isBefore(OffsetDateTime.now().plusSeconds(1));
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).isEqualTo("previous broker failure");
        assertThat(service.reconcileStagedAttachments()).isZero();
    }

    @Test void recoversMoveBeforeMetadataCommitAndEmptyReservationBeforeMove() throws Exception {
        var first = attachment(event(SettlementStatus.COMMITTED)); String firstPath = first.getStoragePath();
        Path permanent = storage.promoteToPermanent(firstPath);
        var second = attachment(event(SettlementStatus.DISPATCHED));
        Files.createDirectory(storage.getPermanentPath(second.getStoragePath()).getParent());
        assertThat(service.reconcileStagedAttachments()).isEqualTo(2);
        assertThat(first.getStoragePath()).isEqualTo(permanent.toString());
        assertThat(Path.of(firstPath).getParent()).doesNotExist();
        assertThat(second.getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
    }

    @Test void compensatesFailedAndCompensatedWithoutDeletingPermanent() throws Exception {
        var first = attachment(event(SettlementStatus.FAILED));
        var second = attachment(event(SettlementStatus.COMPENSATED));
        Path permanent = storage.promoteToPermanent(second.getStoragePath());
        assertThat(service.reconcileStagedAttachments()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(AttachmentStatus.PURGED);
        assertThat(second.getStatus()).isEqualTo(AttachmentStatus.PURGED);
        assertThat(permanent).hasContent("proof");
        assertThat(service.reconcileStagedAttachments()).isZero();
    }

    @Test void preservesMissingContentReferenceAndReportsItWhileOtherItemsRecover() throws Exception {
        var a = attachment(event(SettlementStatus.COMMITTED)); String path = a.getStoragePath();
        storage.compensateStaging(path);
        attachment(event(SettlementStatus.COMMITTED));
        var report = service.reconcileAll();
        assertThat(report.attachmentsReconciled()).isEqualTo(1);
        assertThat(report.failures()).anyMatch(s -> s.contains("Content absent"));
        assertThat(registry.get("ledgerstream.reconciliation.runs").counter().count()).isEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.attachments.recovered").counter().count()).isEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.failures").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.duration").timer().count()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(AttachmentStatus.STAGED); assertThat(a.getStoragePath()).isEqualTo(path);
    }

    @Test void leavesUncommittedEventsAlone() throws Exception {
        var a = attachment(event(SettlementStatus.STAGED));
        assertThat(service.reconcileStagedAttachments()).isZero();
        assertThat(Path.of(a.getStoragePath())).exists();
    }

    @Test void promotionIoFailureIsIsolatedAndNextCycleRecovers() throws Exception {
        var a = attachment(event(SettlementStatus.COMMITTED)); attachment(event(SettlementStatus.COMMITTED));
        doThrow(new IOException("disk unavailable")).doCallRealMethod().when(storage).promoteToPermanent(a.getStoragePath());
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(AttachmentStatus.STAGED);
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
    }

    @Test void dbLockFailureIsIsolatedAndNextCycleRecovers() throws Exception {
        var a = attachment(event(SettlementStatus.COMMITTED)); attachment(event(SettlementStatus.COMMITTED));
        when(events.findLockedById(a.getSettlementEvent().getId())).thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(Optional.of(a.getSettlementEvent()));
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
        assertThat(Path.of(a.getStoragePath())).exists();
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
    }

    @Test void revalidatesCandidateUnderLock() throws Exception {
        var a = attachment(event(SettlementStatus.COMMITTED));
        when(events.findLockedById(a.getSettlementEvent().getId())).thenAnswer(i -> {
            a.updateStatus(AttachmentStatus.PERMANENT); return Optional.of(a.getSettlementEvent());
        });
        assertThat(service.reconcileStagedAttachments()).isZero();
        verify(storage, never()).promoteToPermanent(anyString());
    }

    @Test void onlyUnblocksWhenEveryAttachmentIsPermanent() throws Exception {
        var event = event(SettlementStatus.COMMITTED); attachment(event); var missing = attachment(event);
        storage.compensateStaging(missing.getStoragePath()); var entry = entry(event);
        var deferred = OffsetDateTime.now().plusDays(1); entry.deferWithoutAttempt(deferred);
        assertThat(service.reconcileStagedAttachments()).isEqualTo(1);
        assertThat(entry.getAvailableAt()).isEqualTo(deferred);
    }

    @Test void terminalOutboxEntriesAreNeverResurrectedOrRewritten() throws Exception {
        for (OutboxStatus status : List.of(OutboxStatus.FAILED, OutboxStatus.PUBLISHED)) {
            var event = event(SettlementStatus.COMMITTED); attachment(event); var entry = entry(event);
            if (status == OutboxStatus.FAILED) entry.markFailed("quarantine"); else entry.markPublished(OffsetDateTime.now());
            var at = entry.getAvailableAt();
            service.reconcileStagedAttachments();
            assertThat(entry.getStatus()).isEqualTo(status); assertThat(entry.getAvailableAt()).isEqualTo(at);
            assertThat(entry.getPayload()).isEqualTo("{\"immutable\":true}");
        }
    }

    @Test void reconstructsExactContractAndRemainsIdempotent() throws Exception {
        var event = event(SettlementStatus.COMMITTED); attachment(event);
        assertThat(service.reconcileOutboxIntegrity()).isEqualTo(1);
        var entry = outboxRows.get(event.getId());
        var payload = mapper.readValue(entry.getPayload(), SettlementAcceptedPayload.class);
        assertThat(payload).isEqualTo(new SettlementAcceptedPayload(entry.getId(), "1.0", "settlement.accepted",
                event.getId(), "CARD_PAYOUT", "SYNTH", "USD", "12.34", null, event.getCreatedAt(),
                List.of(new SettlementAcceptedPayload.AttachmentMetadata("proof.txt", "text/plain", 5L))));
        assertThat(entry.getPayloadChecksum()).isEqualTo(event.getPayloadChecksum());
        assertThat(entry.getIdempotencyKey()).isEqualTo(event.getIdempotencyKey());
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(service.reconcileOutboxIntegrity()).isZero();
        assertThat(outboxRows.get(event.getId()).getId()).isEqualTo(entry.getId());
    }

    @Test void synchronizesBothDirectionsPreservingIdentityAndData() {
        var committed = event(SettlementStatus.COMMITTED); var published = entry(committed);
        published.markPublished(OffsetDateTime.now()); var publishedAt = published.getPublishedAt();
        var dispatched = event(SettlementStatus.DISPATCHED); var pending = entry(dispatched);
        pending.recordFailure("history", OffsetDateTime.now().plusDays(1));
        UUID id = pending.getId(); String payload = pending.getPayload();
        assertThat(service.reconcileOutboxIntegrity()).isEqualTo(2);
        assertThat(committed.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        assertThat(published.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(pending.getId()).isEqualTo(id); assertThat(pending.getPayload()).isEqualTo(payload);
        assertThat(pending.getAttempts()).isEqualTo(1); assertThat(pending.getLastError()).isEqualTo("history");
        assertThat(service.reconcileOutboxIntegrity()).isZero();
    }

    @Test void failedOutboxForDispatchedEventRemainsFailed() {
        var event = event(SettlementStatus.DISPATCHED); var entry = entry(event); entry.markFailed("quarantine");
        assertThat(service.reconcileOutboxIntegrity()).isZero();
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test void scanFailuresAreReportedWithoutStoppingOtherPhases() {
        when(attachments.findByStatus(AttachmentStatus.STAGED)).thenThrow(new IllegalStateException("scan offline"));
        event(SettlementStatus.COMMITTED);
        var report = service.reconcileAll();
        assertThat(report.outboxRepairs()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(registry.get("ledgerstream.reconciliation.outbox.reconstructed").counter().count()).isEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.last.failures").gauge().value()).isEqualTo(1);
        assertThatThrownBy(() -> report.failures().add("mutable")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void scheduledRunHonorsEnabledWhileManualRecoveryIsAvailable() {
        properties.setEnabled(false); service.scheduledReconciliation();
        verifyNoInteractions(events, attachments, outbox);
        assertThat(registry.get("ledgerstream.reconciliation.runs").counter().count()).isZero();
        event(SettlementStatus.COMMITTED);
        assertThat(service.reconcileAll().outboxRepairs()).isEqualTo(1);
        assertThat(registry.get("ledgerstream.reconciliation.runs").counter().count()).isEqualTo(1);
    }

    @Test void rejectsNegativeGracePeriod() {
        assertThatThrownBy(() -> service.reconcileOrphanStagingFiles(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
