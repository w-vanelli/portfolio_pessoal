-- V6__add_consumer_idempotency_and_projection.sql

CREATE TABLE consumer_idempotency (
    message_id UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE settlement_projection (
    message_id UUID PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    settlement_type VARCHAR(32) NOT NULL,
    original_settlement_id BIGINT,
    accepted_at TIMESTAMP WITH TIME ZONE,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_settlement_projection_account ON settlement_projection(account_id);
