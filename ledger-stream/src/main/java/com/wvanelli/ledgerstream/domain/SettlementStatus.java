package com.wvanelli.ledgerstream.domain;

/**
 * Represents the lifecycle status of a financial settlement event.
 */
public enum SettlementStatus {
    /**
     * File staged and transaction pending or in-flight.
     */
    STAGED,

    /**
     * Transaction committed to the relational database.
     */
    COMMITTED,

    /**
     * Dispatched to message broker for asynchronous processing.
     */
    DISPATCHED,

    /**
     * Ingestion or validation failed.
     */
    FAILED,

    /**
     * Staging files compensated/cleaned up after transaction rollback or failure.
     */
    COMPENSATED
}
