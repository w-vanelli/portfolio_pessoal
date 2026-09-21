-- A durable publication intent is accepted atomically with settlement_events.
CREATE TABLE settlement_outbox (
    id UUID PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_checksum VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    CONSTRAINT uk_settlement_outbox_settlement UNIQUE (settlement_id),
    CONSTRAINT fk_settlement_outbox_event FOREIGN KEY (settlement_id)
        REFERENCES settlement_events(id) ON DELETE RESTRICT,
    CONSTRAINT chk_settlement_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_settlement_outbox_dispatch
    ON settlement_outbox (status, available_at, created_at);
