-- ShiftGrab initial schema. See docs/design/architecture.md §5 and ADR-0005.
-- btree_gist lets an EXCLUDE constraint combine equality (user_id) with range overlap (time_range).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    keycloak_sub    VARCHAR(64)   NOT NULL,
    full_name       VARCHAR(150)  NOT NULL,
    email           VARCHAR(254)  NOT NULL,
    role            VARCHAR(16)   NOT NULL,
    hourly_rate     NUMERIC(12,2) NOT NULL DEFAULT 25000.00,
    reminder_opt_in BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('MANAGER', 'STAFF')),
    CONSTRAINT ck_users_hourly_rate CHECK (hourly_rate >= 0)
);

CREATE TABLE shift_templates (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    weekdays    SMALLINT[]   NOT NULL,           -- ISO-8601: 1 = Monday ... 7 = Sunday
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL,
    max_slots   INT          NOT NULL,
    open_before INTERVAL     NOT NULL,           -- registration opens this long before start
    valid_from  DATE         NOT NULL,
    valid_to    DATE         NOT NULL,
    created_by  BIGINT       NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_templates_slots CHECK (max_slots > 0),
    CONSTRAINT ck_templates_dates CHECK (valid_to >= valid_from),
    CONSTRAINT ck_templates_weekdays CHECK (weekdays <@ ARRAY[1,2,3,4,5,6,7]::SMALLINT[] AND cardinality(weekdays) > 0)
);

CREATE TABLE shifts (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    start_at             TIMESTAMPTZ  NOT NULL,
    end_at               TIMESTAMPTZ  NOT NULL,
    registration_open_at TIMESTAMPTZ  NOT NULL,
    max_slots            INT          NOT NULL,
    booked_slots         INT          NOT NULL DEFAULT 0,
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    template_id          BIGINT       REFERENCES shift_templates (id),
    created_by           BIGINT       NOT NULL REFERENCES users (id),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_shifts_time CHECK (end_at > start_at),
    CONSTRAINT ck_shifts_open CHECK (registration_open_at < start_at),
    CONSTRAINT ck_shifts_max_slots CHECK (max_slots > 0),
    -- BR-01: last line of defence against overbooking
    CONSTRAINT ck_shifts_slots CHECK (booked_slots BETWEEN 0 AND max_slots),
    CONSTRAINT ck_shifts_status CHECK (status IN ('OPEN', 'FULL', 'CANCELLED'))
);

CREATE INDEX idx_shifts_start_status ON shifts (start_at, status);

CREATE TABLE shift_registrations (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shift_id             BIGINT        NOT NULL REFERENCES shifts (id),
    user_id              BIGINT        NOT NULL REFERENCES users (id),
    status               VARCHAR(16)   NOT NULL DEFAULT 'CONFIRMED',
    -- Copy of the shift's [start_at, end_at): an EXCLUDE constraint can only see one table.
    time_range           TSTZRANGE     NOT NULL,
    hourly_rate_snapshot NUMERIC(12,2) NOT NULL,   -- BR-10
    registered_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cancelled_at         TIMESTAMPTZ,
    CONSTRAINT ck_registrations_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_registrations_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- BR-03: a user can never hold two overlapping confirmed shifts, even under concurrency
    CONSTRAINT ex_registrations_no_overlap
        EXCLUDE USING gist (user_id WITH =, time_range WITH &&) WHERE (status = 'CONFIRMED')
);

-- BR-02: one active registration per user and shift; re-claiming after cancel is allowed
CREATE UNIQUE INDEX uq_registrations_active ON shift_registrations (shift_id, user_id) WHERE status = 'CONFIRMED';
CREATE INDEX idx_registrations_user_active ON shift_registrations (user_id) WHERE status = 'CONFIRMED';

CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(64)  NOT NULL,
    target_id   BIGINT,
    details     JSONB,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit_log (actor_id, occurred_at DESC);
