-- Identity, auth token storage and catalogue skeleton.

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ORGANISER', 'ADMIN')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'BANNED')),
    token_version INT NOT NULL DEFAULT 0,
    display_name  VARCHAR(120),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Email identity is case-insensitive; role/status are never client-settable.
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE access_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL, -- sha256 hex; raw token never stored
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_access_tokens_hash ON access_tokens (token_hash);
CREATE INDEX ix_access_tokens_user ON access_tokens (user_id);

-- Refresh token families enable rotation + reuse detection.
CREATE TABLE refresh_families (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ROTATED', 'REUSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_families_user ON refresh_families (user_id);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id   UUID NOT NULL REFERENCES refresh_families(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

-- Controlled eligibility facts. Unknown age requirement is never inferred.
CREATE TABLE user_eligibility (
    user_id              UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    minimum_verified_age INT CHECK (minimum_verified_age IS NULL OR minimum_verified_age >= 0),
    source               VARCHAR(40),
    verified_at          TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ
);

-- User discovery preferences; location stored coarse (city-level string).
CREATE TABLE user_preferences (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    categories      TEXT[] NOT NULL DEFAULT '{}',
    coarse_location VARCHAR(80),
    radius_km       INT CHECK (radius_km BETWEEN 1 AND 100),
    budget_minor    BIGINT CHECK (budget_minor IS NULL OR budget_minor >= 0),
    time_windows    JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE organisers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    name          VARCHAR(160) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_organisers_owner ON organisers (owner_user_id);

CREATE TABLE organiser_follows (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organiser_id UUID NOT NULL REFERENCES organisers(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, organiser_id)
);

CREATE TABLE venues (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    address    TEXT,
    city       VARCHAR(120),
    country    VARCHAR(2),
    location   geography(Point, 4326) NOT NULL,
    timezone   VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_venues_location ON venues USING GIST (location);

CREATE TABLE events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organiser_id    UUID NOT NULL REFERENCES organisers(id),
    venue_id        UUID REFERENCES venues(id),
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    category        VARCHAR(60) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    age_requirement INT,
    policy_version  INT NOT NULL DEFAULT 1,
    policy          JSONB NOT NULL,
    cover_image_url VARCHAR(400),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
CREATE INDEX ix_events_status_starts ON events (status, starts_at);
CREATE INDEX ix_events_organiser ON events (organiser_id);
CREATE INDEX ix_events_category ON events (category);

CREATE TABLE ticket_tiers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    currency        CHAR(3) NOT NULL,
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    sale_start_at   TIMESTAMPTZ NOT NULL,
    sale_end_at     TIMESTAMPTZ NOT NULL,
    per_user_limit  INT NOT NULL CHECK (per_user_limit > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, name)
);
CREATE INDEX ix_tiers_event ON ticket_tiers (event_id);

CREATE TABLE saved_events (
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id  UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    saved_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_id)
);
CREATE INDEX ix_saved_events_event ON saved_events (event_id);
