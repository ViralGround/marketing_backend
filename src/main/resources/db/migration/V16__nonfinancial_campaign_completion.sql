-- Expand-only, old-backend-compatible managed-beta completion marker.
-- The persisted enum is SETTLED, a terminal state every old binary can
-- deserialize and will not re-process through company submission actions;
-- the new API presents content_approved_at rows as COMPLETED.
ALTER TABLE campaign_applications
    ADD COLUMN content_approved_at TIMESTAMP;

ALTER TABLE campaign_applications
    ADD CONSTRAINT ck_nonfinancial_completion
        CHECK (
            content_approved_at IS NULL
            OR (
                status = 'SETTLED'
                AND reward_paid_amount IS NULL
                AND settled_at IS NULL
            )
        );

COMMENT ON COLUMN campaign_applications.content_approved_at IS
    'Nonfinancial managed-beta completion time; API state is COMPLETED while persisted status stays terminal SETTLED for old-backend compatibility';
