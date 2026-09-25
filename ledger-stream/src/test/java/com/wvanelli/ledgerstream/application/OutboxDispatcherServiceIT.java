package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.AbstractMessagingIntegrationTest;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link OutboxDispatcherService} using real PostgreSQL and
 * RabbitMQ via Testcontainers. These tests verify that transactional atomicity
 * (PUBLISHED + DISPATCHED in the same commit) and broker delivery hold against
 * real infrastructure.
 *
 * <p>The automatic scheduler is disabled by setting a very high poll interval in
 * the messaging-integration-test profile; dispatch is triggered explicitly.
 */
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class OutboxDispatcherServiceIT extends AbstractMessagingIntegrationTest {

    @Autowired private OutboxDispatcherService dispatcherService;
    @Autowired private SettlementEventRepository eventRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private SettlementOutboxRepository outboxRepository;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
        rabbitAdmin.purgeQueue("ledger.settlement.events", false);
    }

    @Test
    @DisplayName("Real dispatch: ACK without Return → PUBLISHED + DISPATCHED atomically, message delivered")
    void shouldDispatchMessageToRabbitMQAndTransitionStatus() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-123", "USD", new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT, "Test"
        );
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);

        String payload = "{\"messageId\":\"" + UUID.randomUUID() + "\",\"test\":true}";
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, payload);
        outboxRepository.saveAndFlush(entry);

        dispatcherService.dispatchPendingEntries();

        // Verify database states are consistent
        SettlementOutboxEntry updatedEntry = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(updatedEntry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updatedEntry.getPublishedAt()).isNotNull();

        SettlementEvent updatedEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);

        // Verify the message arrived in RabbitMQ with correct properties
        org.springframework.amqp.core.Message message = rabbitTemplate.receive("ledger.settlement.events", 2000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(payload);
        // Stable event identity in the AMQP header
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(entry.getId().toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    @DisplayName("Outbox entry with available_at in the future is not selected")
    void shouldNotSelectFutureEntries() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-456", "EUR", new MonetaryAmount("50.00"),
                SettlementType.CARD_PAYOUT, "Future test"
        );
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);

        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"future\":true}");
        // Defer to the future
        entry.deferWithoutAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        outboxRepository.saveAndFlush(entry);

        dispatcherService.dispatchPendingEntries();

        // Should not have been dispatched
        SettlementOutboxEntry stillPending = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(OutboxStatus.PENDING);

        SettlementEvent stillCommitted = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(stillCommitted.getStatus()).isEqualTo(SettlementStatus.COMMITTED);

        // No message in the queue
        org.springframework.amqp.core.Message noMessage = rabbitTemplate.receive("ledger.settlement.events", 500);
        assertThat(noMessage).isNull();
    }

    @Test
    @DisplayName("Attachment still STAGED -> deferred, not published")
    void shouldDeferWhenAttachmentNotPromoted() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-789", "BRL", new MonetaryAmount("200.00"),
                SettlementType.CARD_PAYOUT, "Staged attachment test"
        );
        SettlementAttachment attachment = new SettlementAttachment("doc.pdf", 512L, "application/pdf", "/staging/content");
        event.addAttachment(attachment);
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);

        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"attachment\":\"staged\"}");
        outboxRepository.saveAndFlush(entry);

        dispatcherService.dispatchPendingEntries();

        // Should remain PENDING with no attempts consumed
        SettlementOutboxEntry deferred = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(deferred.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(deferred.getAttempts()).isEqualTo(0);

        // No message should have been sent
        org.springframework.amqp.core.Message noMessage = rabbitTemplate.receive("ledger.settlement.events", 500);
        assertThat(noMessage).isNull();
    }

    @Test
    @DisplayName("DB failure after broker ACK rolls back transaction -> stays PENDING, message is delivered (duplicate on retry)")
    void shouldRollbackTransactionIfSaveFailsAfterBrokerAck() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-000", "USD", new MonetaryAmount("10.00"),
                SettlementType.CARD_PAYOUT, "Rollback test"
        );
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);

        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"rollback\":true}");
        outboxRepository.saveAndFlush(entry);

        // Simulate DB failure on the final saveAndFlush inside the transaction
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated DB error"))
                .when(outboxRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(e ->
                        e.getId().equals(entry.getId()) && e.getStatus() == OutboxStatus.PUBLISHED));

        dispatcherService.dispatchPendingEntries();

        // Transaction rolled back, so DB state remains PENDING/COMMITTED
        SettlementOutboxEntry dbEntry = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(dbEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);

        SettlementEvent dbEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(dbEvent.getStatus()).isEqualTo(SettlementStatus.COMMITTED);

        // But the message WAS sent to RabbitMQ and cannot be rolled back
        org.springframework.amqp.core.Message message = rabbitTemplate.receive("ledger.settlement.events", 2000);
        assertThat(message).isNotNull();
    }
}
