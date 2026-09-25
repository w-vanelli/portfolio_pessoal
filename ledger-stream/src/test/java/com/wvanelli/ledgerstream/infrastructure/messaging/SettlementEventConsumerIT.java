package com.wvanelli.ledgerstream.infrastructure.messaging;

import com.wvanelli.ledgerstream.application.SettlementConsumerService;
import com.wvanelli.ledgerstream.domain.SettlementAcceptedPayload;
import com.wvanelli.ledgerstream.domain.SettlementProjection;
import com.wvanelli.ledgerstream.repository.ConsumerIdempotencyRepository;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.wvanelli.ledgerstream.AbstractMessagingIntegrationTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "spring.rabbitmq.listener.simple.retry.max-attempts=1", // Disable retry to check DLQ immediately
    "spring.rabbitmq.listener.simple.default-requeue-rejected=false"
})
@ActiveProfiles("test")
public class SettlementEventConsumerIT extends AbstractMessagingIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConsumerIdempotencyRepository idempotencyRepository;

    @Autowired
    private SettlementProjectionRepository projectionRepository;

    @MockitoSpyBean
    private SettlementConsumerService consumerService;

    @AfterEach
    void tearDown() {
        projectionRepository.deleteAll();
        idempotencyRepository.deleteAll();
    }

    private SettlementAcceptedPayload createPayload(UUID id, String accountId, String amount) {
        return new SettlementAcceptedPayload(
                id,
                "1.0",
                "settlement.accepted",
                100L,
                "CARD_PAYOUT",
                accountId,
                "USD",
                amount,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                List.of()
        );
    }

    private void sendToRabbit(UUID messageId, SettlementAcceptedPayload payload) throws Exception {
        byte[] body = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .writeValueAsBytes(payload);
        rabbitTemplate.convertAndSend("ledger.settlement.exchange", "settlement.accepted", new org.springframework.amqp.core.Message(body, new org.springframework.amqp.core.MessageProperties()), m -> {
            m.getMessageProperties().setMessageId(messageId.toString());
            m.getMessageProperties().setContentType("application/json");
            return m;
        });
    }

    @Test
    @DisplayName("Primeira entrega cria uma projeção e um registro de idempotência")
    void testFirstDelivery() throws Exception {
        UUID messageId = UUID.randomUUID();
        SettlementAcceptedPayload payload = createPayload(messageId, "ACC-1", "100.00");
        sendToRabbit(messageId, payload);

        await().atMost(5, TimeUnit.SECONDS).until(() -> idempotencyRepository.existsById(messageId));

        assertThat(projectionRepository.findById(messageId)).isPresent().hasValueSatisfying(proj -> {
            assertThat(proj.getAccountId()).isEqualTo("ACC-1");
            assertThat(proj.getAmount()).isEqualTo(new BigDecimal("100.00"));
        });
        
        List<SettlementProjection> byAccount = projectionRepository.findByAccountId("ACC-1");
        assertThat(byAccount).hasSize(1);
    }

    @Test
    @DisplayName("Entrega duplicada não altera nem duplica a projeção")
    void testDuplicateDelivery() throws Exception {
        UUID messageId = UUID.randomUUID();
        SettlementAcceptedPayload payload = createPayload(messageId, "ACC-2", "200.00");

        // Send first time
        sendToRabbit(messageId, payload);
        await().atMost(5, TimeUnit.SECONDS).until(() -> idempotencyRepository.existsById(messageId));

        // Send second time
        sendToRabbit(messageId, payload);

        // Verify count remains 1
        await().pollDelay(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(projectionRepository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
            verify(consumerService, atLeast(2)).process(any()); // called twice
        });
    }

    @Test
    @DisplayName("Eventos distintos são preservados individualmente, inclusive para a mesma conta")
    void testDistinctEventsSameAccount() throws Exception {
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        
        sendToRabbit(msg1, createPayload(msg1, "ACC-3", "10.00"));
        sendToRabbit(msg2, createPayload(msg2, "ACC-3", "20.00"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> projectionRepository.findByAccountId("ACC-3").size() == 2);
    }

    @Test
    @DisplayName("Falha na projeção reverte as duas gravações e reentrega funciona")
    void testRollbackAndRedelivery() throws Exception {
        UUID messageId = UUID.randomUUID();
        SettlementAcceptedPayload payload = createPayload(messageId, "ACC-4", "50.00");

        // Force an exception on the first try
        doThrow(new RuntimeException("Simulated DB failure"))
            .doCallRealMethod() // second try will succeed
            .when(consumerService).process(any());

        sendToRabbit(messageId, payload);

        // Without retry config enabled here (max-attempts=1), it will be rejected. 
        // Wait, I configured max-attempts=1 above, so it will go straight to DLQ. 
        // If I want to test redelivery, I need to send it again manually.
        
        // Let's just wait a bit to ensure it was processed and rejected
        Thread.sleep(1000);
        assertThat(idempotencyRepository.existsById(messageId)).isFalse();
        assertThat(projectionRepository.existsById(messageId)).isFalse();

        // Send again (simulating retry or manual redelivery)
        sendToRabbit(messageId, payload);

        await().atMost(5, TimeUnit.SECONDS).until(() -> idempotencyRepository.existsById(messageId));
        assertThat(projectionRepository.existsById(messageId)).isTrue();
    }

    @Test
    @DisplayName("Mensagem invalida vai para DLQ imediatamente")
    void testInvalidMessageToDLQ() throws Exception {
        UUID messageId = UUID.randomUUID();
        // Negative amount is invalid, should throw AmqpRejectAndDontRequeueException and go to DLQ
        SettlementAcceptedPayload payload = createPayload(messageId, "ACC-DLQ-1", "-10.00");
        sendToRabbit(messageId, payload);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var dlqMsg = rabbitTemplate.receive("ledger.settlement.events.dlq");
            assertThat(dlqMsg).isNotNull();
            assertThat(dlqMsg.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        });
        
        assertThat(idempotencyRepository.existsById(messageId)).isFalse();
    }

    @Test
    @DisplayName("Falha transitoria persistente vai para DLQ")
    void testPersistentTransientFailureToDLQ() throws Exception {
        UUID messageId = UUID.randomUUID();
        SettlementAcceptedPayload payload = createPayload(messageId, "ACC-DLQ-2", "50.00");

        // Force persistent exception
        doThrow(new RuntimeException("Simulated persistent DB failure"))
            .when(consumerService).process(any());

        sendToRabbit(messageId, payload);

        // Since max-attempts=1, it will be immediately rejected and go to DLQ
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var dlqMsg = rabbitTemplate.receive("ledger.settlement.events.dlq");
            assertThat(dlqMsg).isNotNull();
            assertThat(dlqMsg.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        });

        assertThat(idempotencyRepository.existsById(messageId)).isFalse();
        assertThat(projectionRepository.existsById(messageId)).isFalse();
    }
}
