ALTER TABLE upload_records
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

UPDATE upload_records
SET status = 'UPLOADED'
WHERE uploaded_at IS NOT NULL;

ALTER TABLE upload_records
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE upload_records
    ADD CONSTRAINT chk_upload_records_status
        CHECK (status IN ('PENDING', 'UPLOADED'));

CREATE INDEX idx_upload_records_owner_status
    ON upload_records(owner_id, status, created_at);
