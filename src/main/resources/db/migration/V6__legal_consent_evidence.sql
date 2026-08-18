CREATE TABLE member_consent_evidence (
    id BIGSERIAL PRIMARY KEY,
    member_id INTEGER NOT NULL,
    consent_type VARCHAR(64) NOT NULL,
    document_version VARCHAR(80) NOT NULL,
    agreed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_member_consent_member
        FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_consent_type_version
        UNIQUE (member_id, consent_type, document_version),
    CONSTRAINT ck_member_consent_type
        CHECK (consent_type IN (
            'TERMS_OF_SERVICE',
            'PRIVACY_POLICY',
            'AGE_14_CONFIRMATION',
            'CREATOR_THIRD_PARTY_PROVISION',
            'MARKETING_COMMUNICATION'
        )),
    CONSTRAINT ck_member_consent_document_version_not_blank
        CHECK (length(btrim(document_version)) > 0)
);

CREATE INDEX idx_member_consent_agreed_at
    ON member_consent_evidence(agreed_at);

COMMENT ON TABLE member_consent_evidence IS
    'Append-only evidence of consent to an immutable legal document version; intentionally excludes IP and User-Agent';

CREATE OR REPLACE FUNCTION reject_member_consent_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'member consent evidence is append-only; % is forbidden', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_member_consent_evidence_append_only
    BEFORE UPDATE OR DELETE ON member_consent_evidence
    FOR EACH ROW
    EXECUTE FUNCTION reject_member_consent_evidence_mutation();
