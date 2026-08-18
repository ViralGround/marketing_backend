CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64),
    actor_id INTEGER,
    actor_role VARCHAR(24),
    action VARCHAR(48) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(96),
    outcome VARCHAR(24) NOT NULL,
    reason VARCHAR(240),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs(actor_id, created_at);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);

CREATE TABLE refresh_tokens (
    token_id VARCHAR(64) PRIMARY KEY,
    member_id INTEGER NOT NULL REFERENCES members(id),
    family_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by VARCHAR(64)
);
CREATE INDEX idx_refresh_tokens_member ON refresh_tokens(member_id, revoked_at);

CREATE TABLE upload_records (
    file_key VARCHAR(180) PRIMARY KEY,
    owner_id INTEGER NOT NULL REFERENCES members(id),
    content_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    category VARCHAR(24) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_upload_records_owner ON upload_records(owner_id, created_at);
