-- SG-52 / NFR-10: the audit log is append-only. A trigger enforces it in every environment, including local
-- development where the application connects as the table owner and database grants alone would not stop it.
CREATE FUNCTION audit_log_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

-- Managers browse the newest entries first and filter by actor or action (SG-52).
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, occurred_at DESC);
