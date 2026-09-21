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
     * Durable database acceptance; attachment promotion and dispatch may remain pending.
     */
    COMMITTED,

    /**
     * Publication confirmed, required routing verified and attachments ready. Not consumption or settlement.
     */
    DISPATCHED,

    /**
     * Preparation definitively abandoned before durable acceptance.
     */
    FAILED,

    /**
     * Cleanup of abandoned pre-acceptance preparation actually completed.
     */
    COMPENSATED
}
