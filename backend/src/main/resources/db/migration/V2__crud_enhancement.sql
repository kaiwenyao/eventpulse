ALTER TABLE events ADD COLUMN summary VARCHAR(300);
ALTER TABLE events ADD COLUMN venue_name VARCHAR(200);
ALTER TABLE events ADD COLUMN address VARCHAR(400);
ALTER TABLE events ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE events ADD COLUMN longitude DOUBLE PRECISION;
ALTER TABLE events ADD COLUMN ends_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN cover_url VARCHAR(500);
ALTER TABLE events ADD COLUMN cover_asset_id BIGINT;
ALTER TABLE events ADD COLUMN sales_start_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN sales_end_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN max_quantity_per_booking INT NOT NULL DEFAULT 10;
ALTER TABLE events ADD COLUMN contact_info VARCHAR(300);
ALTER TABLE events ADD COLUMN attendance_notes TEXT;
ALTER TABLE events ADD COLUMN cancellation_reason VARCHAR(500);
ALTER TABLE events ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE events ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE events ADD COLUMN archive_note VARCHAR(500);

UPDATE events SET ends_at = starts_at + INTERVAL '3 hours' WHERE ends_at IS NULL;
UPDATE events SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE events ALTER COLUMN ends_at SET NOT NULL;

ALTER TABLE bookings ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN organiser_note VARCHAR(500);

ALTER TABLE notifications ALTER COLUMN booking_id DROP NOT NULL;
ALTER TABLE notifications ADD COLUMN user_id BIGINT;
ALTER TABLE notifications ADD COLUMN event_id BIGINT;
ALTER TABLE notifications ADD COLUMN type VARCHAR(50);
ALTER TABLE notifications ADD COLUMN title VARCHAR(200);
ALTER TABLE notifications ADD COLUMN payload TEXT;
ALTER TABLE notifications ADD COLUMN dedup_key VARCHAR(200);
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;

UPDATE notifications SET type = 'BOOKING', title = '预订通知' WHERE type IS NULL;

CREATE UNIQUE INDEX ux_notifications_dedup_key ON notifications (dedup_key) WHERE dedup_key IS NOT NULL;

CREATE TABLE event_audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT       NOT NULL REFERENCES events (id),
    operator_id  BIGINT       NOT NULL,
    action       VARCHAR(50)  NOT NULL,
    before_data  TEXT,
    after_data   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE event_favourites (
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_id)
);

CREATE TABLE tickets (
    id                 BIGSERIAL PRIMARY KEY,
    booking_id         BIGINT      NOT NULL REFERENCES bookings (id),
    event_id           BIGINT      NOT NULL REFERENCES events (id),
    ticket_code_hash   VARCHAR(64) NOT NULL UNIQUE,
    ticket_code_cipher TEXT        NOT NULL,
    status             VARCHAR(20) NOT NULL,
    checked_in_at      TIMESTAMPTZ,
    checked_in_by      BIGINT,
    check_in_source    VARCHAR(50),
    revoked_at         TIMESTAMPTZ,
    revoked_by         BIGINT,
    revocation_reason  VARCHAR(300),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_assets (
    id           BIGSERIAL PRIMARY KEY,
    owner_id     BIGINT       NOT NULL REFERENCES users (id),
    storage_key  VARCHAR(300) NOT NULL,
    public_url   VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE TABLE user_preferences (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id),
    categories VARCHAR(300),
    cities     VARCHAR(300),
    latitude   DOUBLE PRECISION,
    longitude  DOUBLE PRECISION,
    radius_km  DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE interactions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recommendation_requests (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL UNIQUE,
    user_id             BIGINT,
    partition_key       VARCHAR(80),
    model_version       VARCHAR(40)  NOT NULL,
    feature_version     VARCHAR(40)  NOT NULL,
    frozen_candidates   TEXT         NOT NULL,
    queried_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(80)  NOT NULL,
    event_type   VARCHAR(80)  NOT NULL,
    payload      TEXT         NOT NULL,
    dedup_key    VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE event_daily_metrics (
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    metric_date DATE        NOT NULL,
    views       INT         NOT NULL DEFAULT 0,
    clicks      INT         NOT NULL DEFAULT 0,
    saves       INT         NOT NULL DEFAULT 0,
    unsaves     INT         NOT NULL DEFAULT 0,
    bookings    INT         NOT NULL DEFAULT 0,
    tickets     INT         NOT NULL DEFAULT 0,
    cancels     INT         NOT NULL DEFAULT 0,
    check_ins   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, metric_date)
);

CREATE INDEX ix_events_organiser_status ON events (organiser_id, status);
CREATE INDEX ix_events_public_starts ON events (status, starts_at);
CREATE INDEX ix_tickets_event_status ON tickets (event_id, status);
CREATE INDEX ix_bookings_event ON bookings (event_id);
