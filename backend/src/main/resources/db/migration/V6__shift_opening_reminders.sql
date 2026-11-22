-- SG-42: one row per shift whose opening reminder was triggered. The primary key makes "remind once" a database
-- guarantee: a second attempt (another instance, a retry, a restart) inserts nothing and therefore sends nothing.
CREATE TABLE shift_opening_reminders
(
    shift_id     BIGINT      NOT NULL PRIMARY KEY REFERENCES shifts (id),
    triggered_at TIMESTAMPTZ NOT NULL
);
