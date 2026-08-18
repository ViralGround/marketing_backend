COMMENT ON TABLE audit_logs IS
    'Append-only security and business audit trail; request bodies and direct personal data are excluded';

CREATE OR REPLACE FUNCTION reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit log is append-only; % is forbidden', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_log_mutation();
