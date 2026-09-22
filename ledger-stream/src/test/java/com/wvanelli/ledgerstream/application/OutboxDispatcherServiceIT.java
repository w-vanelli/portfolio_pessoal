package com.wvanelli.ledgerstream.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.AbstractMessagingIntegrationTest;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherServiceIT extends AbstractMessagingIntegrationTest {

    @Autowired
    private OutboxDispatcherService dispatcherService;

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementOutboxRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
        rabbitAdmin.purgeQueue("ledger.settlement.events", false);
    }

    @Test
    void shouldDispatchMessageToRabbitMQAndTransitionStatus() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-123", "USD", new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT, "Test"
        );
        event.transitionTo(SettlementStatus.COMMITTED);
        event = eventRepository.saveAndFlush(event);
        
        SettlementOutboxEntry entry = new SettlementOutboxEntry(event, "{\"messageId\":\"123\"}");
        outboxRepository.saveAndFlush(entry);

        dispatcherService.dispatchPendingEntries();

        // Verify Database
        SettlementOutboxEntry updatedEntry = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(updatedEntry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        SettlementEvent updatedEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);

        // Verify RabbitMQ
        org.springframework.amqp.core.Message message = rabbitTemplate.receive("ledger.settlement.events", 2000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"messageId\":\"123\"}");
    }
}

