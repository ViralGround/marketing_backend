CREATE TABLE marketing_consent_events (
    id BIGSERIAL PRIMARY KEY,
    member_id INTEGER NOT NULL,
    action VARCHAR(16) NOT NULL,
    document_version VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_marketing_consent_event_member
        FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE RESTRICT,
    CONSTRAINT ck_marketing_consent_event_action
        CHECK (action IN ('OPT_IN', 'OPT_OUT')),
    CONSTRAINT ck_marketing_consent_event_version
        CHECK (length(btrim(document_version)) BETWEEN 1 AND 80)
);

CREATE INDEX idx_marketing_consent_member_time
    ON marketing_consent_events(member_id, occurred_at);

COMMENT ON TABLE marketing_consent_events IS
    'Append-only evidence of marketing consent opt-in and withdrawal events';

CREATE OR REPLACE FUNCTION reject_marketing_consent_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'marketing consent event is append-only; % is forbidden', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_marketing_consent_events_append_only
    BEFORE UPDATE OR DELETE ON marketing_consent_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_marketing_consent_event_mutation();
