package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.AttachmentStatus;
import com.wvanelli.ledgerstream.domain.OutboxStatus;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementOutboxEntry;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.infrastructure.messaging.DispatcherProperties;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduled dispatcher that reads PENDING outbox entries whose {@code available_at} has
 * arrived and publishes them to RabbitMQ with correlated publisher confirms.
 *
 * <h3>Identity semantics</h3>
 * <ul>
 *   <li><b>Outbox entry UUID</b> ({@code entry.getId()}) — stable logical event identity,
 *       set as the AMQP {@code messageId} header. Consumers must use this for
 *       idempotent processing; repeated deliveries carry the same ID.</li>
 *   <li><b>Correlation ID per attempt</b> — a fresh UUID per send attempt, used only for
 *       matching the broker's {@code CorrelationData.Confirm} to the sending thread.
 *       Not visible to the consumer.</li>
 * </ul>
 *
 * <h3>Timeout semantics</h3>
 * The five-second timeout limits the wait for the broker's publisher confirm. Timeout
 * is treated as <b>unknown outcome</b>: the broker may have received and routed the
 * message. The entry consumes the existing retry budget and the failed metric records
 * the unsuccessful local processing outcome, not proof of broker rejection.
 * Consumers must be prepared for duplicates.
 *
 * <h3>Attachment readiness</h3>
 * Before publishing, the dispatcher verifies that every attachment of the settlement
 * has reached {@link AttachmentStatus#PERMANENT}. If any attachment is still in staging,
 * publication is deferred: the entry is rescheduled without incrementing the attempt
 * counter, preserving the full retry budget for actual broker failures.
 *
 * <h3>Transaction boundaries</h3>
 * Each entry is processed in its own {@code REQUIRES_NEW} transaction. Broker
 * interaction happens inside this transaction so that the resulting state update
 * ({@code PUBLISHED}/{@code DISPATCHED} or failure recording) commits atomically.
 * If the database commit fails after a broker ACK, the entry remains {@code PENDING}
 * and will be republished — a documented at-least-once trade-off.
 *
 * <h3>DLQ distinction</h3>
 * Marking an outbox entry {@code FAILED} is a local database record of exhausted
 * publication retries. It does <b>not</b> cause the RabbitMQ broker to dead-letter
 * anything. Dead-lettering occurs independently when the broker rejects or expires
 * a message that was already accepted into a queue.
 */
@Service
public class OutboxDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    private final SettlementOutboxRepository outboxRepository;
    private final SettlementEventRepository eventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final DispatcherProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final String exchangeName;

    private final MeterRegistry registry;
    private final Counter attempts, published, failed, deferred;
    private final Timer duration;
    private enum Outcome { SKIPPED, DEFERRED, PUBLISHED, FAILED }

    public OutboxDispatcherService(SettlementOutboxRepository outboxRepository,
                                   SettlementEventRepository eventRepository,
                                   RabbitTemplate rabbitTemplate, DispatcherProperties properties,
                                   PlatformTransactionManager transactionManager, String exchangeName) {
        this(outboxRepository, eventRepository, rabbitTemplate, properties, transactionManager,
                exchangeName, new SimpleMeterRegistry());
    }

    @Autowired
    public OutboxDispatcherService(SettlementOutboxRepository outboxRepository,
                                   SettlementEventRepository eventRepository,
                                   RabbitTemplate rabbitTemplate,
                                   DispatcherProperties properties,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${ledgerstream.queue.exchange:ledger.settlement.exchange}") String exchangeName,
                                   MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.exchangeName = exchangeName;
        this.registry = registry;
        attempts = registry.counter("ledgerstream.dispatcher.attempts");
        published = registry.counter("ledgerstream.dispatcher.published");
        failed = registry.counter("ledgerstream.dispatcher.failed");
        deferred = registry.counter("ledgerstream.dispatcher.deferred");
        duration = registry.timer("ledgerstream.dispatcher.duration");
    }

    /**
     * Polls for PENDING outbox entries whose {@code available_at} has arrived and
     * dispatches them sequentially. Runs on a single-instance scheduler with
     * {@code fixedDelay} to avoid overlap.
     */
    @Scheduled(fixedDelayString = "${ledgerstream.dispatcher.poll-interval-ms:5000}",
               initialDelayString = "${ledgerstream.dispatcher.initial-delay-ms:5000}")
    public void dispatchPendingEntries() {
        Timer.Sample sample = Timer.start(registry);
        try {
            List<SettlementOutboxEntry> pendingBatch;
            try {
                pendingBatch = outboxRepository
                        .findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
                                OutboxStatus.PENDING, OffsetDateTime.now(ZoneOffset.UTC));
            } catch (RuntimeException ex) {
                failed.increment();
                throw ex;
            }
            for (SettlementOutboxEntry entry : pendingBatch) {
                try {
                    processSingleEntry(entry.getId());
                } catch (Exception ex) {
                    failed.increment(); // One failure per item, including a failed commit after NACK.
                    log.error("Failed to process outbox entry {}", entry.getId(), ex);
                }
            }
        } finally {
            sample.stop(duration);
        }
    }

    private void processSingleEntry(UUID entryId) {
        Outcome outcome = transactionTemplate.execute(status -> {
            SettlementOutboxEntry entry = outboxRepository.findLockedById(entryId).orElseThrow();
            if (entry.getStatus() != OutboxStatus.PENDING) {
                return Outcome.SKIPPED;
            }

            // --- Attachment readiness gate ---
            // Verify all attachments have been promoted to permanent storage.
            // If any are still STAGED, defer without consuming a retry attempt.
            SettlementEvent event = eventRepository.findLockedById(entry.getSettlementId()).orElseThrow();
            if (!allAttachmentsReady(event)) {
                log.info("Deferring dispatch of entry {} (settlement {}): attachments not yet promoted to permanent storage",
                        entry.getId(), entry.getSettlementId());
                OffsetDateTime retryAt = OffsetDateTime.now(ZoneOffset.UTC)
                        .plus(properties.getInitialBackoffMs(), java.time.temporal.ChronoUnit.MILLIS);
                entry.deferWithoutAttempt(retryAt);
                outboxRepository.saveAndFlush(entry);
                return Outcome.DEFERRED;
            }

            // --- Build AMQP message ---
            // The outbox entry UUID is the stable logical event identity (messageId).
            // Each send attempt uses a unique correlation ID for broker confirm matching.
            String correlationId = UUID.randomUUID().toString();
            Message message = MessageBuilder.withBody(entry.getPayload().getBytes(StandardCharsets.UTF_8))
                    .andProperties(MessagePropertiesBuilder.newInstance()
                            .setContentType("application/json")
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                            .setMessageId(entry.getId().toString())
                            .setHeader("idempotencyKey", entry.getIdempotencyKey().toString())
                            .build())
                    .build();
            message.getMessageProperties().setHeader("mandatory", true);

            CorrelationData correlationData = new CorrelationData(correlationId);

            Outcome result = Outcome.FAILED;
            try {
                attempts.increment();
                rabbitTemplate.send(exchangeName, "settlement.accepted", message, correlationData);

                // Wait up to 5 seconds for the broker's publisher confirm.
                CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);

                if (confirm.isAck() && correlationData.getReturned() == null) {
                    // Success: broker accepted and routed the message.
                    entry.markPublished(OffsetDateTime.now(ZoneOffset.UTC));
                    event.transitionTo(SettlementStatus.DISPATCHED);
                    result = Outcome.PUBLISHED;
                    log.debug("Broker acknowledged outbox entry {} (settlement {}, correlation {})",
                            entry.getId(), entry.getSettlementId(), correlationId);
                } else if (!confirm.isAck()) {
                    String reason = "Broker NACK: " + confirm.getReason();
                    log.warn("NACK for entry {} (correlation {}): {}", entry.getId(), correlationId, reason);
                    handleFailure(entry, reason);
                } else {
                    // ACK with Return: broker accepted but could not route (no matching binding).
                    String reason = "Message returned (no route): replyCode="
                            + correlationData.getReturned().getReplyCode()
                            + ", replyText=" + correlationData.getReturned().getReplyText();
                    log.warn("Return for entry {} (correlation {}): {}", entry.getId(), correlationId, reason);
                    handleFailure(entry, reason);
                }
            } catch (TimeoutException te) {
                // Timeout: broker may have received the message. Treat as unknown outcome.
                // An attempt was made, so it must consume the retry budget.
                String reason = "Confirm timeout: Outcome unknown. Possible duplicate delivery on next attempt.";
                log.warn("Confirm timeout for entry {} (correlation {}). {}", entry.getId(), correlationId, reason);
                handleFailure(entry, reason);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String reason = "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("Dispatch exception for entry {} (correlation {}): {}",
                        entry.getId(), correlationId, reason, e);
                handleFailure(entry, reason);
            }

            outboxRepository.saveAndFlush(entry);
            return result;
        });
        // A broker ACK alone is insufficient: publish and deferral counts require DB commit.
        switch (outcome) {
            case PUBLISHED -> published.increment();
            case DEFERRED -> deferred.increment();
            case FAILED -> failed.increment();
            case SKIPPED -> { }
        }
    }

    /**
     * Returns {@code true} if the settlement has no attachments, or all attachments
     * have reached {@link AttachmentStatus#PERMANENT}.
     */
    private boolean allAttachmentsReady(SettlementEvent event) {
        List<SettlementAttachment> attachments = event.getAttachments();
        if (attachments.isEmpty()) {
            return true;
        }
        return attachments.stream()
                .allMatch(a -> a.getStatus() == AttachmentStatus.PERMANENT);
    }

    private void handleFailure(SettlementOutboxEntry entry, String reason) {
        if (entry.getAttempts() + 1 >= properties.getMaxAttempts()) {
            entry.markFailed(reason);
            log.error("Outbox entry {} reached max attempts ({}). Marked FAILED. "
                    + "Settlement {} remains COMMITTED with payload and files preserved.",
                    entry.getId(), properties.getMaxAttempts(), entry.getSettlementId());
        } else {
            long delay = (long) (properties.getInitialBackoffMs()
                    * Math.pow(properties.getBackoffMultiplier(), entry.getAttempts()));
            delay = Math.min(delay, properties.getMaxBackoffMs());
            long jitter = ThreadLocalRandom.current().nextLong(0, delay / 2 + 1);
            OffsetDateTime retryAt = OffsetDateTime.now(ZoneOffset.UTC)
                    .plus(delay + jitter, java.time.temporal.ChronoUnit.MILLIS);
            entry.recordFailure(reason, retryAt);
            log.info("Outbox entry {} rescheduled: attempt={}, retryAt={}, reason={}",
                    entry.getId(), entry.getAttempts(), retryAt, reason);
        }
    }
}
