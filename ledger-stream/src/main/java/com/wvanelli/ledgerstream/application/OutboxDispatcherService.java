package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.OutboxStatus;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementOutboxEntry;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.infrastructure.messaging.DispatcherProperties;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OutboxDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    private final SettlementOutboxRepository outboxRepository;
    private final SettlementEventRepository eventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final DispatcherProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final String exchangeName;

    public OutboxDispatcherService(SettlementOutboxRepository outboxRepository,
                                   SettlementEventRepository eventRepository,
                                   RabbitTemplate rabbitTemplate,
                                   DispatcherProperties properties,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${ledgerstream.queue.exchange:ledger.settlement.exchange}") String exchangeName) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.exchangeName = exchangeName;
    }

    @Scheduled(fixedDelayString = "${ledgerstream.dispatcher.poll-interval-ms:5000}", initialDelayString = "${ledgerstream.dispatcher.initial-delay-ms:5000}")
    public void dispatchPendingEntries() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<SettlementOutboxEntry> pendingBatch = outboxRepository
                .findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus.PENDING, now);

        for (SettlementOutboxEntry entry : pendingBatch) {
            try {
                processSingleEntry(entry.getId());
            } catch (Exception e) {
                log.error("Failed to process outbox entry {}", entry.getId(), e);
            }
        }
    }

    private void processSingleEntry(java.util.UUID entryId) {
        transactionTemplate.executeWithoutResult(status -> {
            SettlementOutboxEntry entry = outboxRepository.findById(entryId).orElseThrow();
            if (entry.getStatus() != OutboxStatus.PENDING) {
                return; 
            }

            Message message = MessageBuilder.withBody(entry.getPayload().getBytes(StandardCharsets.UTF_8))
                    .andProperties(MessagePropertiesBuilder.newInstance()
                            .setContentType("application/json")
                            .setMessageId(entry.getId().toString())
                            .build())
                    .build();

            CorrelationData correlationData = new CorrelationData(entry.getId().toString());

            try {
                rabbitTemplate.send(exchangeName, "settlement.accepted", message, correlationData);

                CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);

                if (confirm.isAck() && correlationData.getReturned() == null) {
                    entry.markPublished(OffsetDateTime.now(ZoneOffset.UTC));
                    
                    SettlementEvent event = eventRepository.findById(entry.getSettlementId()).orElseThrow();
                    event.transitionTo(SettlementStatus.DISPATCHED);
                    
                } else if (!confirm.isAck()) {
                    handleFailure(entry, "Broker NACK: " + confirm.getReason());
                } else {
                    handleFailure(entry, "Message returned: " + correlationData.getReturned().getReplyText());
                }
            } catch (Exception e) {
                handleFailure(entry, "Exception: " + e.getMessage());
            }

            outboxRepository.saveAndFlush(entry);
        });
    }

    private void handleFailure(SettlementOutboxEntry entry, String reason) {
        if (entry.getAttempts() + 1 >= properties.getMaxAttempts()) {
            entry.markFailed(reason);
        } else {
            long delay = (long) (properties.getInitialBackoffMs() * Math.pow(properties.getBackoffMultiplier(), entry.getAttempts()));
            delay = Math.min(delay, properties.getMaxBackoffMs());
            long jitter = ThreadLocalRandom.current().nextLong(0, delay / 2 + 1);
            OffsetDateTime retryAt = OffsetDateTime.now(ZoneOffset.UTC).plus(delay + jitter, java.time.temporal.ChronoUnit.MILLIS);
            entry.recordFailure(reason, retryAt);
        }
    }
}
