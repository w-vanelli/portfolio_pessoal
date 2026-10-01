package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.infrastructure.messaging.DispatcherProperties;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OutboxDispatcherService}. Exercises dispatch logic deterministically
 * using mocks for repository and broker interactions. Uses a fake transaction manager that
 * commits synchronously, so state mutations are immediately observable.
 *
 * <p>These tests verify the dispatcher's decision logic (success, NACK, Return, timeout,
 * max attempts, attachment readiness). Transactional atomicity against a real database
 * is validated by {@link OutboxDispatcherServiceIT}.
 */
class OutboxDispatcherServiceTest {

    private SettlementOutboxRepository outboxRepository;
    private SettlementEventRepository eventRepository;
    private RabbitTemplate rabbitTemplate;
    private DispatcherProperties properties;
    private OutboxDispatcherService dispatcherService;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(SettlementOutboxRepository.class);
        eventRepository = mock(SettlementEventRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        properties = new DispatcherProperties();
        properties.setMaxAttempts(3);

        PlatformTransactionManager transactionManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
        dispatcherService = new OutboxDispatcherService(
                outboxRepository, eventRepository, rabbitTemplate, properties, transactionManager, "exchange"
        );
    }

    private SettlementEvent committedEvent() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-123", "USD", new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT, "Test"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", 1L);
        event.transitionTo(SettlementStatus.COMMITTED);
        return event;
    }

    private SettlementEvent committedEventWithStagedAttachment() {
        SettlementEvent event = committedEvent();
        SettlementAttachment attachment = new SettlementAttachment("proof.pdf", 1024L, "application/pdf", "/staging/content");
        event.addAttachment(attachment);
        return event;
    }

    private SettlementEvent committedEventWithPermanentAttachment() {
        SettlementEvent event = committedEvent();
        SettlementAttachment attachment = new SettlementAttachment("proof.pdf", 1024L, "application/pdf", "/permanent/content");
        attachment.updateStatus(AttachmentStatus.PERMANENT);
        event.addAttachment(attachment);
        return event;
    }

    private void stubBatchAndEntry(SettlementOutboxEntry entry, SettlementEvent event) {
        when(outboxRepository.findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any()))
                .thenReturn(List.of(entry));
        when(outboxRepository.findLockedById(entry.getId())).thenReturn(Optional.of(entry));
        when(eventRepository.findLockedById(event.getId())).thenReturn(Optional.of(event));
    }

    private void stubBrokerAck() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));
    }

    private void stubBrokerNack(String reason) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, reason));
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));
    }

    private void stubBrokerAckWithReturn(int replyCode, String replyText) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            Message msg = invocation.getArgument(2);
            cd.setReturned(new ReturnedMessage(msg, replyCode, replyText, "exchange", "settlement.accepted"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));
    }

    private void stubBrokerTimeout() {
        doAnswer(invocation -> {
            // Don't complete the future — will cause TimeoutException on get(5, SECONDS)
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));
    }

    // --- Success scenarios ---

    @Test
    @DisplayName("ACK without Return and all attachments ready → PUBLISHED + DISPATCHED")
    void shouldDispatchSuccessfullyAndTransitionStatus() {
        SettlementEvent event = committedEventWithPermanentAttachment();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"test\":true}");
        stubBatchAndEntry(entry, event);
        stubBrokerAck();

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(entry.getPublishedAt()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        verify(outboxRepository).saveAndFlush(entry);
    }

    @Test
    @DisplayName("ACK without Return and no attachments → PUBLISHED + DISPATCHED")
    void shouldDispatchEventWithNoAttachments() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"test\":true}");
        stubBatchAndEntry(entry, event);
        stubBrokerAck();

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
    }

    @Test
    @DisplayName("AMQP message carries stable event ID as messageId and PERSISTENT deliveryMode")
    void shouldSetCorrectMessageProperties() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"test\":true}");
        stubBatchAndEntry(entry, event);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), messageCaptor.capture(), correlationCaptor.capture());

        dispatcherService.dispatchPendingEntries();

        Message sent = messageCaptor.getValue();
        // messageId should be the outbox entry UUID (stable across retries)
        assertThat(sent.getMessageProperties().getMessageId()).isEqualTo(entry.getId().toString());
        // Delivery mode must be PERSISTENT
        assertThat(sent.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        // Correlation ID is per-attempt, different from the messageId
        assertThat(correlationCaptor.getValue().getId()).isNotEqualTo(entry.getId().toString());
        // Body should be the persisted payload verbatim
        assertThat(new String(sent.getBody())).isEqualTo("{\"test\":true}");
    }

    // --- Failure scenarios ---

    @Test
    @DisplayName("Broker NACK → failure recorded, entry remains PENDING")
    void shouldHandleBrokerNack() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);
        stubBrokerNack("Queue full");

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("Broker NACK: Queue full");
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        verify(outboxRepository).saveAndFlush(entry);
    }

    @Test
    @DisplayName("ACK with Return (no binding) → failure recorded, entry remains PENDING")
    void shouldHandleAckWithReturn() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);
        stubBrokerAckWithReturn(312, "NO_ROUTE");

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("Message returned (no route)");
        assertThat(entry.getLastError()).contains("NO_ROUTE");
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
    }

    @Test
    @DisplayName("Timeout -> unknown outcome, failure recorded, attempts incremented")
    void shouldConsumeAttemptOnTimeout() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);
        stubBrokerTimeout();

        dispatcherService.dispatchPendingEntries();

        // Should count as a failure attempt
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getLastError()).contains("Confirm timeout: Outcome unknown");
        // Should be deferred to the future
        assertThat(entry.getAvailableAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
    }

    @Test
    @DisplayName("Consecutive timeouts exhaust max attempts -> FAILED, settlement stays COMMITTED")
    void shouldMarkFailedAfterMaxTimeouts() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);
        stubBrokerTimeout();

        // Exhaust all attempts
        for (int i = 0; i < properties.getMaxAttempts(); i++) {
            // Re-stub since findById needs to return updated entry
            when(outboxRepository.findLockedById(entry.getId())).thenReturn(Optional.of(entry));
            dispatcherService.dispatchPendingEntries();
        }

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(entry.getAttempts()).isEqualTo(properties.getMaxAttempts());
        assertThat(entry.getLastError()).contains("Confirm timeout: Outcome unknown");
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
    }

    @Test
    @DisplayName("Max attempts reached → FAILED, settlement stays COMMITTED")
    void shouldMarkFailedAfterMaxAttempts() {
        SettlementEvent event = committedEvent();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);
        stubBrokerNack("persistent failure");

        // Exhaust all attempts
        for (int i = 0; i < properties.getMaxAttempts(); i++) {
            // Re-stub since findById needs to return updated entry
            when(outboxRepository.findLockedById(entry.getId())).thenReturn(Optional.of(entry));
            dispatcherService.dispatchPendingEntries();
        }

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(entry.getAttempts()).isEqualTo(properties.getMaxAttempts());
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
    }

    // --- Attachment readiness ---

    @Test
    @DisplayName("Attachment still in STAGED → deferred, not published, no attempt consumed")
    void shouldDeferWhenAttachmentNotReady() {
        SettlementEvent event = committedEventWithStagedAttachment();
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        stubBatchAndEntry(entry, event);

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(0);
        // Should not attempt to send to broker at all
        verify(rabbitTemplate, never()).send(any(), any(), any(Message.class), any(CorrelationData.class));
        verify(outboxRepository).saveAndFlush(entry);
    }
}
