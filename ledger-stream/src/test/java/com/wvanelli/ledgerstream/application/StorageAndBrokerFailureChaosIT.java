package com.wvanelli.ledgerstream.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.AbstractMessagingIntegrationTest;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Chaos integration test validating broker-fault tolerance of the outbox pattern.
 *
 * <p>Phase 1 – Broker fault injection: injects an {@link AmqpException} on the
 * {@code send(exchange,routingKey,message,CorrelationData)} overload via
 * {@code @MockitoSpyBean}, calls the dispatcher, and asserts the entry remains
 * {@code PENDING} with {@code attempts=1} and a populated {@code lastError}.
 * The associated event must stay in {@code COMMITTED}. No projection is created.
 *
 * <p>Phase 2 – Reconciliation does not republish PENDING: calls
 * {@code reconcileOutboxIntegrity()} and asserts the entry is still {@code PENDING}
 * (reconciliation fixes structural gaps; it does not replay pending dispatches).
 *
 * <p>Phase 3 – Self-healing via natural backoff: removes the injected fault and
 * waits via Awaitility until the entry's {@code availableAt} (set by the dispatcher's
 * exponential back-off with {@code initial-backoff-ms=100}) has elapsed, confirming
 * that a future retry-at was persisted after the fault. The attempt counter is verified
 * to still be {@code 1}. Then the dispatcher is called again, and the test awaits
 * outbox {@code PUBLISHED}, event {@code DISPATCHED}, and projection creation, proving
 * self-recovery without any manual state mutation.
 *
 * <p>The scheduler starts after a long initial delay; the AMQP listener
 * container runs normally so the consumer can create the downstream projection.
 * {@code ledgerstream.dispatcher.initial-backoff-ms=100} is declared here so the
 * back-off window after the injected fault is short enough to observe within the test.
 */
@SpringBootTest(properties = {
        "ledgerstream.dispatcher.initial-backoff-ms=100",
        "ledgerstream.dispatcher.initial-delay-ms=999999999",
        "ledgerstream.reconciliation.enabled=false"
})
class StorageAndBrokerFailureChaosIT extends AbstractMessagingIntegrationTest {

    @MockitoSpyBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxDispatcherService dispatcherService;

    @Autowired
    private SettlementReconciliationService reconciliationService;

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementOutboxRepository outboxRepository;

    @Autowired
    private SettlementProjectionRepository projectionRepository;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        projectionRepository.deleteAll();
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
        rabbitAdmin.purgeQueue("ledger.settlement.events", false);
        // Remove any leftover stubs from previous tests
        Mockito.reset(rabbitTemplate);
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(rabbitTemplate);
        rabbitAdmin.purgeQueue("ledger.settlement.events", false);
        projectionRepository.deleteAll();
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("Broker fault: PENDING entry stays PENDING with attempt=1 and lastError; no projection; reconcile does not republish; after recovery, PUBLISHED/DISPATCHED and projection created")
    void brokerFaultThenRecoveryProducesProjection() throws Exception {

        // ── Arrange: create COMMITTED event + outbox entry ──────────────────────
        UUID outboxId = UUID.randomUUID();
        SettlementEvent mutableEvent = new SettlementEvent(
                UUID.randomUUID(), "a".repeat(64), "ACC-CHAOS-1", "USD",
                new MonetaryAmount("99.00"), SettlementType.WIRE_TRANSFER, "Chaos broker test");
        mutableEvent.transitionTo(SettlementStatus.COMMITTED);
        mutableEvent = eventRepository.saveAndFlush(mutableEvent);
        // Capture into effectively-final variables for use inside lambdas.
        final SettlementEvent event = mutableEvent;

        // Build payload with messageId == outboxId so the consumer projection's
        // messageId matches the outbox entry UUID (identity contract).
        String payload = objectMapper.writeValueAsString(new SettlementAcceptedPayload(
                outboxId,
                "1.0",
                "settlement.accepted",
                event.getId(),
                SettlementType.WIRE_TRANSFER.name(),
                "ACC-CHAOS-1",
                "USD",
                "99.00",
                null,
                event.getCreatedAt(),
                java.util.List.of()
        ));
        SettlementOutboxEntry entry = new SettlementOutboxEntry(outboxId, event, payload);
        outboxRepository.saveAndFlush(entry);

        // ── Phase 1: inject AmqpException on send() ──────────────────────────────
        Mockito.doThrow(new AmqpException("Injected broker fault for chaos test"))
                .when(rabbitTemplate)
                .send(anyString(), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));

        dispatcherService.dispatchPendingEntries();

        // Entry must be PENDING, attempts incremented to 1, lastError populated.
        SettlementOutboxEntry afterFault = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(afterFault.getStatus())
                .as("Status after broker fault must remain PENDING")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFault.getAttempts())
                .as("Attempt counter must be 1 after one failure")
                .isEqualTo(1);
        assertThat(afterFault.getLastError())
                .as("lastError must be populated after broker fault")
                .isNotBlank()
                .contains("AmqpException", "Injected broker fault for chaos test");
        assertThat(afterFault.getPublishedAt()).isNull();
        assertThat(afterFault.getPayload()).isEqualTo(payload);
        assertThat(afterFault.getSettlementId()).isEqualTo(event.getId());
        assertThat(afterFault.getIdempotencyKey()).isEqualTo(event.getIdempotencyKey());
        assertThat(afterFault.getPayloadChecksum()).isEqualTo(event.getPayloadChecksum());
        assertThat(afterFault.getAvailableAt())
                .as("Backoff retry time must be later than the original outbox creation time")
                .isAfter(entry.getCreatedAt());

        // Associated event must still be COMMITTED (not advanced to DISPATCHED).
        SettlementEvent afterFaultEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterFaultEvent.getStatus())
                .as("Event must remain COMMITTED after failed dispatch")
                .isEqualTo(SettlementStatus.COMMITTED);

        // No projection must exist.
        assertThat(projectionRepository.findBySettlementId(event.getId()))
                .as("No projection should be created after failed dispatch")
                .isEmpty();

        // ── Phase 2: reconcileOutboxIntegrity() must NOT republish PENDING ───────
        // The reconciliation service repairs structural gaps (missing outbox rows,
        // status inconsistencies) — it does not act as a secondary dispatcher.
        assertThat(reconciliationService.reconcileOutboxIntegrity())
                .as("Reconciliation must find no structural inconsistencies")
                .isZero();

        SettlementOutboxEntry afterReconcile = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(afterReconcile.getStatus())
                .as("Reconciliation must not change PENDING to PUBLISHED")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(afterReconcile.getAttempts())
                .as("Reconciliation must not increment attempts")
                .isEqualTo(1);
        assertThat(afterReconcile.getId()).isEqualTo(outboxId);
        assertThat(afterReconcile.getPayload()).isEqualTo(payload);
        assertThat(afterReconcile.getLastError()).isEqualTo(afterFault.getLastError());
        assertThat(afterReconcile.getAvailableAt()).isEqualTo(afterFault.getAvailableAt());
        Mockito.verify(rabbitTemplate).send(anyString(), eq("settlement.accepted"),
                any(Message.class), any(CorrelationData.class));

        assertThat(projectionRepository.findBySettlementId(event.getId()))
                .as("Reconciliation must not create a projection")
                .isEmpty();

        // ── Phase 3: remove fault, wait for natural backoff to expire, dispatch ──

        // Remove the injected stub so the real RabbitTemplate is used.
        Mockito.reset(rabbitTemplate);

        // Let the persisted backoff expire without changing retry state.
        await("backoff window expires")
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    SettlementOutboxEntry pending =
                            outboxRepository.findById(outboxId).orElseThrow();
                    assertThat(pending.getAttempts())
                            .as("attempts must still be 1 — backoff does not increment counter")
                            .isEqualTo(1);
                    assertThat(pending.getAvailableAt())
                            .as("availableAt (backoff) must have elapsed so dispatcher will pick it up")
                            .isBeforeOrEqualTo(OffsetDateTime.now(ZoneOffset.UTC));
                });

        // Automatic dispatch is delayed locally; the consumer runs normally.
        dispatcherService.dispatchPendingEntries();

        // The AMQP listener container runs normally (no auto-startup override) and
        // consumes the published message, creating the downstream projection.
        await("outbox published and projection created")
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Outbox entry must be PUBLISHED.
                    SettlementOutboxEntry published = outboxRepository.findById(outboxId).orElseThrow();
                    assertThat(published.getStatus())
                            .as("Outbox entry must be PUBLISHED after successful dispatch")
                            .isEqualTo(OutboxStatus.PUBLISHED);
                    assertThat(published.getPublishedAt())
                            .as("publishedAt must be set")
                            .isNotNull();
                    assertThat(published.getAttempts()).isEqualTo(1);
                    assertThat(published.getLastError()).isEqualTo(afterFault.getLastError());
                    assertThat(published.getPayload()).isEqualTo(payload);
                    assertThat(published.getAvailableAt()).isEqualTo(afterFault.getAvailableAt());
                    assertThat(published.getIdempotencyKey()).isEqualTo(event.getIdempotencyKey());
                    assertThat(published.getPayloadChecksum()).isEqualTo(event.getPayloadChecksum());

                    // Settlement event must be DISPATCHED.
                    SettlementEvent dispatched = eventRepository.findById(event.getId()).orElseThrow();
                    assertThat(dispatched.getStatus())
                            .as("Event must be DISPATCHED after successful outbox publish")
                            .isEqualTo(SettlementStatus.DISPATCHED);

                    // Projection must exist with the correct messageId (= outboxId).
                    var projection = projectionRepository.findBySettlementId(event.getId());
                    assertThat(projection)
                            .as("Projection must be created after successful dispatch")
                            .isPresent();
                    assertThat(projection.get().getMessageId())
                            .as("Projection messageId must equal the outbox entry UUID")
                            .isEqualTo(outboxId);
                    assertThat(projection.get().getAccountId())
                            .as("Projection accountId must match the settlement")
                            .isEqualTo("ACC-CHAOS-1");
                    assertThat(projection.get().getAmount())
                            .as("Projection amount must match the settlement")
                            .isEqualByComparingTo("99.00");
                    assertThat(projection.get().getSettlementId()).isEqualTo(event.getId());
                    assertThat(projection.get().getCurrency()).isEqualTo("USD");
                    assertThat(projection.get().getSettlementType()).isEqualTo(SettlementType.WIRE_TRANSFER.name());
                    assertThat(projection.get().getOriginalSettlementId()).isNull();
                    assertThat(Math.abs(ChronoUnit.NANOS.between(
                            projection.get().getAcceptedAt(), event.getCreatedAt()))).isLessThanOrEqualTo(1_000L);
                    assertThat(projection.get().getProcessedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("Broker failure followed by missing outbox row is repaired and projected")
    void repairsMissingOutboxAfterBrokerFault() throws Exception {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "b".repeat(64), "ACC-CHAOS-2", "EUR",
                new MonetaryAmount("42.50"), SettlementType.CARD_PAYOUT, "Missing outbox test");
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);
        final SettlementEvent committed = event;

        UUID originalId = UUID.randomUUID();
        String originalPayload = objectMapper.writeValueAsString(new SettlementAcceptedPayload(
                originalId, "1.0", "settlement.accepted", event.getId(),
                event.getSettlementType().name(), event.getAccountId(), event.getCurrency(),
                event.getAmount().toPlainString(), event.getOriginalSettlementId(),
                event.getCreatedAt(), java.util.List.of()));
        outboxRepository.saveAndFlush(new SettlementOutboxEntry(originalId, event, originalPayload));

        Mockito.doThrow(new AmqpException("Injected broker fault before row loss"))
                .when(rabbitTemplate)
                .send(anyString(), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));
        dispatcherService.dispatchPendingEntries();

        SettlementOutboxEntry failed = outboxRepository.findById(originalId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("AmqpException", "Injected broker fault before row loss");
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getPayload()).isEqualTo(originalPayload);
        assertThat(failed.getAvailableAt()).isAfter(failed.getCreatedAt());
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMMITTED);
        assertThat(projectionRepository.findBySettlementId(event.getId())).isEmpty();

        // Simulate loss of this fixture's durable outbox row while the broker fault remains active.
        outboxRepository.deleteById(originalId);
        outboxRepository.flush();
        assertThat(outboxRepository.findBySettlementId(event.getId())).isEmpty();
        dispatcherService.dispatchPendingEntries();
        assertThat(outboxRepository.findBySettlementId(event.getId())).isEmpty();
        assertThat(projectionRepository.findBySettlementId(event.getId())).isEmpty();
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMMITTED);
        Mockito.verify(rabbitTemplate).send(anyString(), eq("settlement.accepted"),
                any(Message.class), any(CorrelationData.class));

        assertThat(reconciliationService.reconcileOutboxIntegrity()).isEqualTo(1);
        SettlementOutboxEntry repaired = outboxRepository.findBySettlementId(event.getId()).orElseThrow();
        assertThat(repaired.getId()).isNotEqualTo(originalId);
        assertThat(repaired.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(repaired.getAttempts()).isZero();
        assertThat(repaired.getLastError()).isNull();
        assertThat(repaired.getPublishedAt()).isNull();
        assertThat(repaired.getSettlementId()).isEqualTo(event.getId());
        assertThat(repaired.getIdempotencyKey()).isEqualTo(event.getIdempotencyKey());
        assertThat(repaired.getPayloadChecksum()).isEqualTo(event.getPayloadChecksum());
        SettlementAcceptedPayload canonical = objectMapper.readValue(repaired.getPayload(), SettlementAcceptedPayload.class);
        assertThat(canonical.messageId()).isEqualTo(repaired.getId());
        assertThat(canonical.schemaVersion()).isEqualTo("1.0");
        assertThat(canonical.eventType()).isEqualTo("settlement.accepted");
        assertThat(canonical.settlementId()).isEqualTo(event.getId());
        assertThat(canonical.settlementType()).isEqualTo(event.getSettlementType().name());
        assertThat(canonical.accountId()).isEqualTo(event.getAccountId());
        assertThat(canonical.currency()).isEqualTo(event.getCurrency());
        assertThat(canonical.amount()).isEqualTo(event.getAmount().toPlainString());
        assertThat(canonical.originalSettlementId()).isNull();
        assertThat(Math.abs(ChronoUnit.NANOS.between(
                canonical.acceptedAt(), event.getCreatedAt()))).isLessThanOrEqualTo(1_000L);
        assertThat(canonical.attachments()).isEmpty();
        assertThat(reconciliationService.reconcileOutboxIntegrity()).isZero();
        assertThat(outboxRepository.findBySettlementId(event.getId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(repaired);
        assertThat(outboxRepository.findById(originalId)).isEmpty();
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMMITTED);
        assertThat(projectionRepository.findBySettlementId(event.getId())).isEmpty();
        Mockito.verify(rabbitTemplate).send(anyString(), eq("settlement.accepted"),
                any(Message.class), any(CorrelationData.class));

        Mockito.reset(rabbitTemplate);
        dispatcherService.dispatchPendingEntries();
        await("repaired entry published and projected")
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    SettlementOutboxEntry published = outboxRepository.findById(repaired.getId()).orElseThrow();
                    assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
                    assertThat(published.getPublishedAt()).isNotNull();
                    assertThat(published.getAttempts()).isZero();
                    assertThat(published.getLastError()).isNull();
                    assertThat(published.getPayload()).isEqualTo(repaired.getPayload());
                    assertThat(published.getAvailableAt()).isEqualTo(repaired.getAvailableAt());
                    assertThat(published.getIdempotencyKey()).isEqualTo(committed.getIdempotencyKey());
                    assertThat(published.getPayloadChecksum()).isEqualTo(committed.getPayloadChecksum());
                    assertThat(eventRepository.findById(committed.getId()).orElseThrow().getStatus())
                            .isEqualTo(SettlementStatus.DISPATCHED);
                    var projection = projectionRepository.findBySettlementId(committed.getId()).orElseThrow();
                    assertThat(projection.getMessageId()).isEqualTo(repaired.getId());
                    assertThat(projection.getSettlementId()).isEqualTo(committed.getId());
                    assertThat(projection.getAccountId()).isEqualTo(committed.getAccountId());
                    assertThat(projection.getCurrency()).isEqualTo(committed.getCurrency());
                    assertThat(projection.getAmount()).isEqualByComparingTo(committed.getAmount().toPlainString());
                    assertThat(projection.getSettlementType()).isEqualTo(committed.getSettlementType().name());
                    assertThat(projection.getOriginalSettlementId()).isNull();
                    assertThat(Math.abs(ChronoUnit.NANOS.between(
                            projection.getAcceptedAt(), committed.getCreatedAt()))).isLessThanOrEqualTo(1_000L);
                    assertThat(projection.getProcessedAt()).isNotNull();
                });
    }
}
