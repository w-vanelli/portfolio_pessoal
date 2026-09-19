-- V2__add_amount_check_and_chargeback_reference.sql
-- Adds domain constraint for strictly positive amounts and schema support
-- for chargeback adjustment references.
--
-- Design decisions (approved):
-- 1. Every settlement amount must be strictly positive (> 0).
--    The CHECK constraint enforces this at the database level,
--    independent of application-layer validation.
-- 2. CHARGEBACK_ADJUSTMENT events reference an original settlement
--    via original_settlement_id. The column is nullable because only
--    chargeback adjustments use it. Validation of the reference
--    (same account, same currency, different type) is performed by the
--    application service, not by database constraints.
-- 3. The original settlement is never modified or deleted by a chargeback.

ALTER TABLE settlement_events
    ADD CONSTRAINT chk_settlement_amount_positive CHECK (amount > 0);

ALTER TABLE settlement_events
    ADD COLUMN original_settlement_id BIGINT;

ALTER TABLE settlement_events
    ADD CONSTRAINT fk_original_settlement
        FOREIGN KEY (original_settlement_id) REFERENCES settlement_events(id);

-- Partial index: only chargeback adjustments have a non-null reference
CREATE INDEX idx_settlements_original_id
    ON settlement_events (original_settlement_id)
    WHERE original_settlement_id IS NOT NULL;
