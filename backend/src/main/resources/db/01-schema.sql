CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    full_name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MANAGER', 'STAFF')),
    hourly_rate REAL DEFAULT 25000.00,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shifts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    shift_name TEXT NOT NULL,
    shift_date TEXT NOT NULL,
    start_time TEXT NOT NULL,
    end_time TEXT NOT NULL,
    max_slots INTEGER NOT NULL CHECK (max_slots > 0),
    booked_slots INTEGER NOT NULL DEFAULT 0 CHECK (booked_slots >= 0),
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FULL', 'CANCELLED')),
    version INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shift_registrations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    shift_id INTEGER NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    registered_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_shift UNIQUE (shift_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_shifts_date_status ON shifts(shift_date, status);
CREATE INDEX IF NOT EXISTS idx_registrations_user ON shift_registrations(user_id);
