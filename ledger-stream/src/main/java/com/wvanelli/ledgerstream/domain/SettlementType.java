package com.wvanelli.ledgerstream.domain;

/**
 * Settlement types supported by LedgerStream.
 */
public enum SettlementType {
    CARD_PAYOUT,
    WIRE_TRANSFER,
    INVOICE_SETTLEMENT,
    CHARGEBACK_ADJUSTMENT
}
