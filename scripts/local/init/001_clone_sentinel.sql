CREATE SCHEMA IF NOT EXISTS preprod_guard;

CREATE TABLE IF NOT EXISTS preprod_guard.clone_sentinel (
    sentinel_id TEXT PRIMARY KEY,
    clone_kind TEXT NOT NULL CHECK (clone_kind IN ('exact', 'sanitized')),
    source_snapshot_id TEXT NOT NULL,
    release_id TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    destroyed_at TIMESTAMP WITH TIME ZONE,
    baseline_started_at TIMESTAMP WITH TIME ZONE,
    baseline_completed_at TIMESTAMP WITH TIME ZONE,
    evidence_seal_sha256 TEXT CHECK (evidence_seal_sha256 ~ '^[0-9a-f]{64}$'),
    e2e_before_evidence_seal_sha256 TEXT
        CHECK (e2e_before_evidence_seal_sha256 ~ '^[0-9a-f]{64}$'),
    e2e_before_recorded_at TIMESTAMP WITH TIME ZONE,
    CHECK (expires_at > created_at),
    CHECK ((baseline_completed_at IS NULL AND evidence_seal_sha256 IS NULL)
        OR (baseline_completed_at IS NOT NULL AND evidence_seal_sha256 IS NOT NULL)),
    CHECK ((e2e_before_recorded_at IS NULL AND e2e_before_evidence_seal_sha256 IS NULL)
        OR (e2e_before_recorded_at IS NOT NULL
            AND e2e_before_evidence_seal_sha256 IS NOT NULL))
);

INSERT INTO preprod_guard.clone_sentinel (
    sentinel_id, clone_kind, source_snapshot_id, release_id, created_at, expires_at
) VALUES (
    'local-synthetic-sentinel',
    'sanitized',
    'no-production-source',
    'vg-local-synthetic',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '30 days'
)
ON CONFLICT (sentinel_id) DO NOTHING;

COMMENT ON TABLE preprod_guard.clone_sentinel IS
    'Safety marker for disposable clone tooling; never create this schema in production';
