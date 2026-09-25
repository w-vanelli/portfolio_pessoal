package com.wvanelli.ledgerstream.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.application.SettlementConsumerService;
import com.wvanelli.ledgerstream.domain.SettlementAcceptedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SettlementEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventConsumer.class);

    private final SettlementConsumerService consumerService;
    private final ObjectMapper objectMapper;

    public SettlementEventConsumer(SettlementConsumerService consumerService, ObjectMapper objectMapper) {
        this.consumerService = consumerService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${ledgerstream.queue.settlement-events}", ackMode = "AUTO")
    public void onMessage(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        log.debug("Received AMQP message, messageId: {}", messageId);

        SettlementAcceptedPayload payload;
        try {
            payload = objectMapper.readValue(message.getBody(), SettlementAcceptedPayload.class);
        } catch (IOException e) {
            log.error("Failed to deserialize message {}: {}. Rejecting (DLQ).", messageId, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Invalid payload format", e);
        }

        if (!"1.0".equals(payload.schemaVersion())) {
             log.error("Unsupported schema version {} for message {}. Rejecting (DLQ).", payload.schemaVersion(), messageId);
             throw new AmqpRejectAndDontRequeueException("Unsupported schema version: " + payload.schemaVersion());
        }

        if (!"settlement.accepted".equals(payload.eventType())) {
            log.error("Unsupported event type {} for message {}. Rejecting (DLQ).", payload.eventType(), messageId);
            throw new AmqpRejectAndDontRequeueException("Unsupported event type: " + payload.eventType());
        }

        if (payload.messageId() == null || !payload.messageId().toString().equals(messageId)) {
            log.error("Payload messageId {} does not match AMQP messageId {}. Rejecting (DLQ).", payload.messageId(), messageId);
            throw new AmqpRejectAndDontRequeueException("Logical ID mismatch");
        }

        if (payload.settlementId() == null || payload.accountId() == null || payload.currency() == null || payload.amount() == null || payload.settlementType() == null) {
            log.error("Missing mandatory fields in payload for message {}. Rejecting (DLQ).", messageId);
            throw new AmqpRejectAndDontRequeueException("Missing mandatory fields");
        }

        try {
            java.math.BigDecimal amt = new java.math.BigDecimal(payload.amount());
            if (amt.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                log.error("Invalid amount {} in message {}. Rejecting (DLQ).", payload.amount(), messageId);
                throw new AmqpRejectAndDontRequeueException("Amount must be strictly positive");
            }
        } catch (NumberFormatException e) {
            log.error("Unparseable amount {} in message {}. Rejecting (DLQ).", payload.amount(), messageId);
            throw new AmqpRejectAndDontRequeueException("Unparseable amount format", e);
        }

        // Delegate to transactional service
        try {
            consumerService.process(payload);
        } catch (Exception e) {
            log.error("Error processing message {}: {}", messageId, e.getMessage());
            throw e; // Let Spring AMQP handle retry/requeue/DLQ according to configuration
        }
    }
}
