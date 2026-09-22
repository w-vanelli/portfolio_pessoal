-- V4__add_payload_to_outbox.sql
-- Add a JSON payload column to the outbox table to ensure immutable contract content across retries.

ALTER TABLE settlement_outbox ADD COLUMN payload TEXT;

-- Fallback for any existing records from V3 (empty JSON object to satisfy NOT NULL constraints if needed)
UPDATE settlement_outbox SET payload = '{}' WHERE payload IS NULL;

ALTER TABLE settlement_outbox ALTER COLUMN payload SET NOT NULL;

