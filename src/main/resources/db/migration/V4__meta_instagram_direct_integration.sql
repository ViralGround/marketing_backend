ALTER TABLE creator_instagram_connections
    ADD COLUMN IF NOT EXISTS encrypted_access_token TEXT,
    ADD COLUMN IF NOT EXISTS access_token_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS token_refreshed_at TIMESTAMP;

UPDATE creator_instagram_connections
SET provider = 'META',
    status = 'DISCONNECTED',
    provider_user_id = NULL,
    provider_account_id = NULL,
    ig_username = NULL,
    last_error = 'Meta Instagram API로 다시 연결해 주세요.'
WHERE provider = 'PHYLLO';

CREATE TABLE IF NOT EXISTS instagram_oauth_states (
    id BIGSERIAL PRIMARY KEY,
    state_hash VARCHAR(64) NOT NULL,
    creator_id INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_instagram_oauth_state_hash UNIQUE (state_hash),
    CONSTRAINT fk_instagram_oauth_state_creator
        FOREIGN KEY (creator_id) REFERENCES members(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_instagram_oauth_state_creator
    ON instagram_oauth_states (creator_id, expires_at);

CREATE TABLE IF NOT EXISTS instagram_webhook_deliveries (
    id BIGSERIAL PRIMARY KEY,
    event_hash VARCHAR(64) NOT NULL,
    entry_count INTEGER NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_instagram_webhook_event_hash UNIQUE (event_hash)
);

CREATE INDEX IF NOT EXISTS idx_instagram_webhook_received
    ON instagram_webhook_deliveries (received_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_creator_instagram_provider_account
    ON creator_instagram_connections (provider_account_id)
    WHERE provider_account_id IS NOT NULL;
