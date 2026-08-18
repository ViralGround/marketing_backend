ALTER TABLE members
    ADD COLUMN withdrawn_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE members DROP CONSTRAINT IF EXISTS ck_members_status;
ALTER TABLE members
    ADD CONSTRAINT ck_members_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'));

ALTER TABLE members
    ADD CONSTRAINT ck_members_withdrawal_timestamp
        CHECK (
            (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
            OR
            (status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
        );

CREATE INDEX idx_members_withdrawn_at
    ON members(withdrawn_at)
    WHERE withdrawn_at IS NOT NULL;

ALTER TABLE campaign_applications
    ADD CONSTRAINT ck_campaign_applications_status
        CHECK (status IN ('PENDING', 'WITHDRAWN', 'APPROVED', 'REJECTED', 'SUBMITTED', 'CHANGES_REQUESTED', 'SETTLED'));

COMMENT ON COLUMN members.withdrawn_at IS
    'Account access and public exposure ended at this time; retained records remain subject to the approved legal retention/anonymization policy';

COMMENT ON COLUMN campaign_applications.status IS
    'PENDING, WITHDRAWN (creator withdrew before selection), APPROVED, REJECTED, SUBMITTED, CHANGES_REQUESTED, or SETTLED';
