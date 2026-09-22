package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.infrastructure.messaging.DispatcherProperties;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override
            protected void doCommit(DefaultTransactionStatus status) {}
            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        dispatcherService = new OutboxDispatcherService(
                outboxRepository, eventRepository, rabbitTemplate, properties, transactionManager, "exchange"
        );
    }

    @Test
    void shouldDispatchSuccessfullyAndTransitionStatus() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-123", "USD", new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT, "Test"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", 1L);
        event.transitionTo(SettlementStatus.COMMITTED);

        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        
        when(outboxRepository.findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any()))
                .thenReturn(List.of(entry));
        when(outboxRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            CompletableFuture<CorrelationData.Confirm> future = new CompletableFuture<>();
            future.complete(new CorrelationData.Confirm(true, null));
            cd.getFuture().complete(future.get());
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(entry.getPublishedAt()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
        verify(outboxRepository).saveAndFlush(entry);
    }

    @Test
    void shouldHandleBrokerNack() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-123", "USD", new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT, "Test"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", 1L);
        event.transitionTo(SettlementStatus.COMMITTED);

        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{}");
        
        when(outboxRepository.findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any()))
                .thenReturn(List.of(entry));
        when(outboxRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            CompletableFuture<CorrelationData.Confirm> future = new CompletableFuture<>();
            future.complete(new CorrelationData.Confirm(false, "Queue full"));
            cd.getFuture().complete(future.get());
            return null;
        }).when(rabbitTemplate).send(eq("exchange"), eq("settlement.accepted"), any(Message.class), any(CorrelationData.class));

        dispatcherService.dispatchPendingEntries();

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("Broker NACK: Queue full");
        verify(outboxRepository).saveAndFlush(entry);
    }
}

