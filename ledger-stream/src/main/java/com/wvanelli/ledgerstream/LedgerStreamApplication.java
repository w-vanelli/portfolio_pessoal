package com.wvanelli.ledgerstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * LedgerStream: High-Reliability Idempotent Event & Settlement Dispatcher.
 *
 * Designed to handle financial settlements with strict idempotency barriers,
 * two-phase file staging storage with transactional compensation, and fault-tolerant
 * asynchronous messaging via RabbitMQ.
 *
 * @author Wellington Vanelli
 */
@SpringBootApplication
@EnableAsync
public class LedgerStreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerStreamApplication.class, args);
    }
}
