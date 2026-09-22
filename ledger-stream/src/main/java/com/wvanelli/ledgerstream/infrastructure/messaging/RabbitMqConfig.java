package com.wvanelli.ledgerstream.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${ledgerstream.queue.exchange:ledger.settlement.exchange}")
    private String exchangeName;

    @Value("${ledgerstream.queue.settlement-events:ledger.settlement.events}")
    private String queueName;

    @Value("${ledgerstream.queue.dlx:ledger.settlement.dlx}")
    private String dlxName;

    @Value("${ledgerstream.queue.settlement-dlq:ledger.settlement.events.dlq}")
    private String dlqName;

    @Bean
    public TopicExchange settlementExchange() {
        return new TopicExchange(exchangeName);
    }

    @Bean
    public Queue settlementQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", dlxName)
                .withArgument("x-dead-letter-routing-key", queueName)
                .build();
    }

    @Bean
    public Binding settlementBinding(Queue settlementQueue, TopicExchange settlementExchange) {
        return BindingBuilder.bind(settlementQueue).to(settlementExchange).with("settlement.accepted");
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(dlxName);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(dlqName).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queueName);
    }
}
