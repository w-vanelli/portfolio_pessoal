-- V1 already created the unused audit table. Evolve it without changing old
-- migrations or manufacturing history for settlements accepted before V8.
ALTER TABLE settlement_audit_log RENAME COLUMN settlement_id TO settlement_event_id;
ALTER TABLE settlement_audit_log ALTER COLUMN event_details DROP NOT NULL;

CREATE INDEX idx_audit_event_created_id
    ON settlement_audit_log (settlement_event_id, created_at, id);
DROP INDEX idx_audit_settlement_id;
