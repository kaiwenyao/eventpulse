-- Recommendation inputs/outputs, notifications, audit and admin re-auth.

CREATE TABLE interactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  VARCHAR(80) NOT NULL,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    session_id  VARCHAR(80),
    event_id    UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL
        CHECK (type IN ('VIEW', 'IMPRESSION', 'SAVE', 'UNSAVE', 'SHARE', 'BOOK_ATTEMPT')),
    position    INT,
    occurred_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- server receive time is the fact; client occurredAt is a claim
CREATE UNIQUE INDEX ux_interactions_dedupe
    ON interactions (request_id, event_id, type, COALESCE(position, -1));
CREATE INDEX ix_interactions_event_time ON interactions (event_id, received_at DESC);
CREATE INDEX ix_interactions_user_time ON interactions (user_id, received_at DESC);

CREATE TABLE recommendation_requests (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    section         VARCHAR(40) NOT NULL,
    model_version   VARCHAR(40) NOT NULL,
    feature_version VARCHAR(40) NOT NULL,
    query_as_of     TIMESTAMPTZ NOT NULL,
    candidate_ids   JSONB NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_rec_requests_expiry ON recommendation_requests (expires_at);

CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(60) NOT NULL,
    payload    JSONB NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

-- Append-only audit trail; business roles have no UPDATE path.
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor         VARCHAR(120) NOT NULL,
    action        VARCHAR(80) NOT NULL,
    resource_type VARCHAR(60) NOT NULL,
    resource_id   VARCHAR(80),
    before_state  JSONB,
    after_state   JSONB,
    reason        TEXT,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_resource ON audit_log (resource_type, resource_id, created_at DESC);

CREATE TABLE admin_reauth_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Recommendation V1 vector column, only when pgvector is present.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        ALTER TABLE events ADD COLUMN embedding vector(64);
        RAISE NOTICE 'events.embedding added (pgvector available)';
    ELSE
        RAISE NOTICE 'pgvector absent; events.embedding skipped';
    END IF;
END $$;
