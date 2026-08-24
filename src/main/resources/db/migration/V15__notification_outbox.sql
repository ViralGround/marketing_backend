CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    notification_kind VARCHAR(80) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    html_body TEXT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    provider_message_id VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_notification_outbox_status
        CHECK (status IN ('PENDING', 'SUPERSEDED', 'SENT', 'DEAD_LETTER')),
    CONSTRAINT ck_notification_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_outbox_sent_at
        CHECK (
            (status = 'SENT' AND sent_at IS NOT NULL AND provider_message_id IS NOT NULL)
            OR status <> 'SENT'
        )
);

CREATE INDEX idx_notification_outbox_due
    ON notification_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';
