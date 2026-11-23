-- SG-50: the hours & pay report reads confirmed registrations by their start time.
CREATE INDEX idx_registrations_confirmed_start ON shift_registrations (lower(time_range))
    WHERE status = 'CONFIRMED';

-- V4 added audit_log indexes that V1 already had under other names (idx_audit_occurred, idx_audit_actor).
-- Duplicate indexes only slow down every insert, so the V4 copies go.
DROP INDEX idx_audit_log_occurred;
DROP INDEX idx_audit_log_actor;
