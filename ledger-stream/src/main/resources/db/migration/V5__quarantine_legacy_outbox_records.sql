-- V5__quarantine_legacy_outbox_records.sql
-- Quarantine records created under V3 that were assigned a dummy '{}' payload in V4.
-- Since they lack a valid payload that can be reconstructed, assigning them
-- '{}' would produce invalid event messages if dispatched.
-- We mark any existing PENDING records with '{}' as FAILED to prevent invalid publication 
-- while preserving the outbox row, settlement, and any associated files for manual review.

UPDATE settlement_outbox
   SET status    = 'FAILED',
       last_error = 'V5 migration: no valid payload available for pre-existing V3 record',
       payload    = '{"_quarantine":"legacy_v3_incomplete"}'
 WHERE payload = '{}'
   AND status = 'PENDING';

-- For non-PENDING records (PUBLISHED, FAILED) that have '{}', assign a migration marker.
-- These records will not be republished, so the payload is inert metadata.
UPDATE settlement_outbox
   SET payload = '{"_quarantine":"legacy_v3_incomplete", "_note":"original payload unavailable"}'
 WHERE payload = '{}';
