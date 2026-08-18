ALTER TABLE contact_requests
    ADD COLUMN privacy_consent_version VARCHAR(80),
    ADD COLUMN privacy_consented_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_contact_privacy_evidence_pair
        CHECK (
            (privacy_consent_version IS NULL AND privacy_consented_at IS NULL)
            OR
            (privacy_consent_version IS NOT NULL
             AND length(btrim(privacy_consent_version)) > 0
             AND privacy_consented_at IS NOT NULL)
        );

COMMENT ON COLUMN contact_requests.privacy_consent_version IS
    'Exact immutable privacy document version shown when this consultation was submitted; NULL means legacy evidence is unknown';

CREATE OR REPLACE FUNCTION reject_contact_privacy_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.privacy_consent_version IS DISTINCT FROM NEW.privacy_consent_version
       OR OLD.privacy_consented_at IS DISTINCT FROM NEW.privacy_consented_at THEN
        RAISE EXCEPTION 'contact privacy consent evidence is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contact_privacy_evidence_immutable
    BEFORE UPDATE ON contact_requests
    FOR EACH ROW
    EXECUTE FUNCTION reject_contact_privacy_evidence_mutation();
