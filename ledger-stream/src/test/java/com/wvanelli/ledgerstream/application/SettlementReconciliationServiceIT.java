package com.wvanelli.ledgerstream.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.infrastructure.messaging.DispatcherProperties;
import com.wvanelli.ledgerstream.repository.*;
import com.wvanelli.ledgerstream.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Real PostgreSQL commits/rollbacks with temporary local filesystem, no test transaction. */
@TestPropertySource(properties = {"ledgerstream.reconciliation.enabled=false",
        "ledgerstream.dispatcher.initial-delay-ms=999999999"})
class SettlementReconciliationServiceIT extends AbstractIntegrationTest {
    @Autowired SettlementEventRepository events;
    @Autowired SettlementAttachmentRepository attachments;
    @Autowired SettlementOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;
    @TempDir Path root;
    StagingStorageServiceImpl storage;
    SettlementReconciliationService service;
    TransactionTemplate tx;
    List<Long> fixtureIds = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        storage = new StagingStorageServiceImpl(root.resolve("staging").toString(), root.resolve("permanent").toString());
        tx = new TransactionTemplate(transactionManager);
        service = service(attachments, outbox);
    }

    SettlementReconciliationService service(SettlementAttachmentRepository attachmentRepo, SettlementOutboxRepository outboxRepo) {
        return new SettlementReconciliationService(events, attachmentRepo, outboxRepo, storage,
                transactionManager, mapper, new ReconciliationProperties());
    }

    @AfterEach void cleanup() {
        tx.executeWithoutResult(status -> {
            for (Long id : fixtureIds.reversed()) {
                outbox.findBySettlementId(id).ifPresent(outbox::delete);
                outbox.flush();
                events.findById(id).ifPresent(events::delete);
                events.flush();
            }
        });
    }

    SettlementEvent fixture(SettlementStatus status, boolean withAttachment, boolean withOutbox) throws Exception {
        StagingTicket ticket = withAttachment ? storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("proof".getBytes())) : null;
        return tx.execute(transaction -> {
            var event = new SettlementEvent(UUID.randomUUID(), "a".repeat(64), "SYNTH-RECOVERY", "USD",
                    new MonetaryAmount("12.34"), SettlementType.CARD_PAYOUT, "synthetic recovery");
            if (ticket != null) event.addAttachment(new SettlementAttachment(ticket.originalFileName(),
                    ticket.fileSizeBytes(), ticket.contentType(), ticket.storagePath()));
            if (status == SettlementStatus.DISPATCHED) event.transitionTo(SettlementStatus.COMMITTED);
            event.transitionTo(status);
            events.saveAndFlush(event); fixtureIds.add(event.getId());
            if (withOutbox) outbox.saveAndFlush(new SettlementOutboxEntry(event, "{\"unchanged\":true}"));
            return event;
        });
    }

    SettlementAttachment attachment(Long id) { return attachments.findBySettlementEventId(id).getFirst(); }

    @Test void recoversMoveBeforeMetadataAndReservationBeforeMoveAndUnblocks() throws Exception {
        var moved = fixture(SettlementStatus.COMMITTED, true, true);
        var reserved = fixture(SettlementStatus.COMMITTED, true, true);
        String oldPath = attachment(moved.getId()).getStoragePath();
        Path permanent = storage.promoteToPermanent(oldPath);
        Files.createDirectory(storage.getPermanentPath(attachment(reserved.getId()).getStoragePath()).getParent());
        tx.executeWithoutResult(status -> {
            var entry = outbox.findBySettlementId(moved.getId()).orElseThrow();
            entry.recordFailure("history", OffsetDateTime.now().plusDays(1)); outbox.saveAndFlush(entry);
        });
        service.reconcileStagedAttachments();
        assertThat(attachment(moved.getId()).getStoragePath()).isEqualTo(permanent.toString());
        assertThat(attachment(reserved.getId()).getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(Path.of(attachment(reserved.getId()).getStoragePath())).hasContent("proof");
        assertThat(Path.of(oldPath).getParent()).doesNotExist();
        var entry = outbox.findBySettlementId(moved.getId()).orElseThrow();
        assertThat(entry.getAttempts()).isEqualTo(1); assertThat(entry.getLastError()).isEqualTo("history");
        assertThat(entry.getAvailableAt()).isBefore(OffsetDateTime.now().plusSeconds(1));
        service.reconcileAll();
        assertThat(attachment(moved.getId()).getStoragePath()).isEqualTo(permanent.toString());
    }

    @Test void databaseFailureAfterMoveRollsBackMetadataAndRetryRepairsIt() throws Exception {
        var event = fixture(SettlementStatus.COMMITTED, true, true);
        var a = attachment(event.getId()); String staging = a.getStoragePath();
        var failing = mock(SettlementAttachmentRepository.class, delegatesTo(attachments));
        doAnswer(invocation -> {
            SettlementAttachment value = invocation.getArgument(0);
            var result = attachments.saveAndFlush(value);
            if (value.getId().equals(a.getId())) throw new IllegalStateException("injected failure after SQL flush");
            return result;
        }).when(failing).saveAndFlush(any());
        service(failing, outbox).reconcileStagedAttachments();
        assertThat(attachment(event.getId()).getStatus()).isEqualTo(AttachmentStatus.STAGED);
        assertThat(attachment(event.getId()).getStoragePath()).isEqualTo(staging);
        assertThat(storage.isStaged(staging)).isFalse(); assertThat(storage.isPermanentlyStored(staging)).isTrue();
        service.reconcileStagedAttachments();
        assertThat(attachment(event.getId()).getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(Path.of(attachment(event.getId()).getStoragePath())).hasContent("proof");
    }

    @Test void compensatesRollbackStateButRetainsMissingReferences() throws Exception {
        var failed = fixture(SettlementStatus.FAILED, true, false);
        var compensated = fixture(SettlementStatus.COMPENSATED, true, false);
        Path keep = storage.promoteToPermanent(attachment(compensated.getId()).getStoragePath());
        var missing = fixture(SettlementStatus.COMMITTED, true, true);
        var reference = attachment(missing.getId()); storage.compensateStaging(reference.getStoragePath());
        service.reconcileStagedAttachments();
        assertThat(attachment(failed.getId()).getStatus()).isEqualTo(AttachmentStatus.PURGED);
        assertThat(attachment(compensated.getId()).getStatus()).isEqualTo(AttachmentStatus.PURGED);
        assertThat(keep).hasContent("proof");
        assertThat(attachment(missing.getId()).getStoragePath()).isEqualTo(reference.getStoragePath());
        assertThat(attachment(missing.getId()).getStatus()).isEqualTo(AttachmentStatus.STAGED);
    }

    @Test void orphanRecoveryConsultsRealDatabaseAndHonorsGrace() throws Exception {
        var event = fixture(SettlementStatus.COMMITTED, true, true);
        var orphan = storage.stage("orphan", "text/plain", new ByteArrayInputStream("orphan".getBytes()));
        Path empty = Files.createDirectory(root.resolve("staging").resolve(UUID.randomUUID().toString()));
        assertThat(service.reconcileOrphanStagingFiles(Duration.ofMinutes(15))).isZero();
        Thread.sleep(50); // Real filesystem clock granularity, only for zero-grace test.
        assertThat(service.reconcileOrphanStagingFiles(Duration.ZERO)).isEqualTo(2);
        assertThat(Path.of(orphan.storagePath()).getParent()).doesNotExist(); assertThat(empty).doesNotExist();
        assertThat(Path.of(attachment(event.getId()).getStoragePath())).hasContent("proof");
    }

    @Test void reconstructsPayloadOnceUnderConcurrentRecovery() throws Exception {
        var event = fixture(SettlementStatus.COMMITTED, true, false);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> recover = () -> { barrier.await(5, TimeUnit.SECONDS); return service.reconcileOutboxIntegrity(); };
            var first = pool.submit(recover); var second = pool.submit(recover);
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        var entry = outbox.findBySettlementId(event.getId()).orElseThrow();
        var payload = mapper.readValue(entry.getPayload(), SettlementAcceptedPayload.class);
        assertThat(payload.messageId()).isEqualTo(entry.getId()); assertThat(payload.settlementId()).isEqualTo(event.getId());
        assertThat(payload.schemaVersion()).isEqualTo("1.0"); assertThat(payload.eventType()).isEqualTo("settlement.accepted");
        assertThat(payload.amount()).isEqualTo("12.34"); assertThat(payload.acceptedAt().toInstant()).isEqualTo(events.findById(event.getId()).orElseThrow().getCreatedAt().toInstant());
        assertThat(payload.attachments()).containsExactly(new SettlementAcceptedPayload.AttachmentMetadata("proof.txt", "text/plain", 5L));
        service.reconcileOutboxIntegrity();
        assertThat(outbox.findBySettlementId(event.getId()).orElseThrow().getId()).isEqualTo(entry.getId());
    }

    @Test void concurrentAttachmentRecoveryHasOneMoveAndConsistentMetadata() throws Exception {
        var event = fixture(SettlementStatus.COMMITTED, true, true);
        String path = attachment(event.getId()).getStoragePath();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> recover = () -> { barrier.await(5, TimeUnit.SECONDS); return service.reconcileStagedAttachments(); };
            var first = pool.submit(recover); var second = pool.submit(recover);
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(attachment(event.getId()).getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(storage.getPermanentPath(path)).hasContent("proof");
    }

    @Test void syncsStatusesWithoutChangingPayloadIdentityOrAttempts() throws Exception {
        var committed = fixture(SettlementStatus.COMMITTED, false, true);
        var dispatched = fixture(SettlementStatus.DISPATCHED, false, true);
        tx.executeWithoutResult(status -> {
            var published = outbox.findBySettlementId(committed.getId()).orElseThrow(); published.markPublished(OffsetDateTime.now());
            outbox.saveAndFlush(published);
        });
        var original = outbox.findBySettlementId(dispatched.getId()).orElseThrow();
        service.reconcileOutboxIntegrity();
        assertThat(events.findById(committed.getId()).orElseThrow().getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        var repaired = outbox.findBySettlementId(dispatched.getId()).orElseThrow();
        assertThat(repaired.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(repaired.getId()).isEqualTo(original.getId()); assertThat(repaired.getPayload()).isEqualTo(original.getPayload());
        assertThat(repaired.getAttempts()).isEqualTo(original.getAttempts());
    }

    @Test void doesNotOverwriteConcurrentDispatcherFailure() throws Exception {
        var event = fixture(SettlementStatus.DISPATCHED, false, true);
        var sending = new CountDownLatch(1); var release = new CountDownLatch(1); var locking = new CountDownLatch(1);
        RabbitTemplate broker = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            sending.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            CorrelationData data = invocation.getArgument(3); data.getFuture().complete(new CorrelationData.Confirm(false, "injected NACK"));
            return null;
        }).when(broker).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var properties = new DispatcherProperties(); properties.setMaxAttempts(1);
        var dispatcherOutbox = mock(SettlementOutboxRepository.class, delegatesTo(outbox));
        doReturn(List.of(outbox.findBySettlementId(event.getId()).orElseThrow())).when(dispatcherOutbox)
                .findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any());
        var dispatcher = new OutboxDispatcherService(dispatcherOutbox, events, broker, properties, transactionManager, "synthetic.exchange");
        var observedOutbox = mock(SettlementOutboxRepository.class, delegatesTo(outbox));
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id.equals(event.getId())) locking.countDown();
            return outbox.findLockedBySettlementId(id);
        }).when(observedOutbox).findLockedBySettlementId(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var dispatch = pool.submit(dispatcher::dispatchPendingEntries);
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                var recovery = pool.submit(() -> service(attachments, observedOutbox).reconcileOutboxIntegrity());
                assertThat(locking.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> recovery.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); dispatch.get(10, TimeUnit.SECONDS); recovery.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        var retained = outbox.findBySettlementId(event.getId()).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(retained.getAttempts()).isEqualTo(1); assertThat(retained.getLastError()).contains("NACK");
    }
}
