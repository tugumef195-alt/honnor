CREATE TABLE IF NOT EXISTS app_users (
    username VARCHAR(320) PRIMARY KEY,
    password VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS authorities (
    username VARCHAR(320) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
    authority VARCHAR(50) NOT NULL,
    CONSTRAINT authorities_unique UNIQUE (username, authority)
);

CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY,
    reference_code VARCHAR(20) NOT NULL UNIQUE,
    access_hash VARCHAR(64) NOT NULL,
    category VARCHAR(120) NOT NULL,
    details_cipher TEXT NOT NULL,
    person_cipher TEXT,
    contact_cipher TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    assigned_to VARCHAR(320),
    public_update TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS reports_created_at_idx ON reports(created_at DESC);
CREATE INDEX IF NOT EXISTS reports_status_idx ON reports(status);

CREATE TABLE IF NOT EXISTS case_actions (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    actor VARCHAR(320) NOT NULL,
    action VARCHAR(40) NOT NULL,
    note_cipher TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS case_actions_report_idx ON case_actions(report_id, created_at DESC);

CREATE TABLE IF NOT EXISTS pattern_reviews (
    first_id UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    second_id UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    decision VARCHAR(16) NOT NULL,
    reviewer VARCHAR(320) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (first_id, second_id),
    CONSTRAINT pattern_order CHECK (first_id < second_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY,
    actor VARCHAR(320) NOT NULL,
    action VARCHAR(60) NOT NULL,
    record_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS audit_log_created_at_idx ON audit_log(created_at);
