-- V1__init_ledger.sql
-- Migration initializing schema for LedgerStream financial settlement engine

CREATE TABLE settlement_events (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key UUID NOT NULL,
    payload_checksum VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    settlement_type VARCHAR(32) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_settlement_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_settlements_account_status ON settlement_events (account_id, status);
CREATE INDEX idx_settlements_created_at ON settlement_events (created_at);

CREATE TABLE settlement_attachments (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_settlement_attachment FOREIGN KEY (settlement_id) REFERENCES settlement_events(id) ON DELETE CASCADE
);

CREATE INDEX idx_attachments_settlement_id ON settlement_attachments (settlement_id);

CREATE TABLE settlement_audit_log (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    previous_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    event_details TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_settlement_audit FOREIGN KEY (settlement_id) REFERENCES settlement_events(id) ON DELETE CASCADE
);

CREATE INDEX idx_audit_settlement_id ON settlement_audit_log (settlement_id);
