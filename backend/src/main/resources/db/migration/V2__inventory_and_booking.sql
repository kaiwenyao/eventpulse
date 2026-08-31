-- Inventory, per-user quota, bookings, reservations and request-fingerprint idempotency.
-- Invariants enforced here:
--   * available + reserved + sold + withheld = capacity (same-row CHECK)
--   * activeQuantity + confirmedQuantity <= perUserLimit (enforced by conditional UPDATE)
--   * one reservation per booking

CREATE TABLE inventory (
    tier_id   UUID PRIMARY KEY REFERENCES ticket_tiers(id) ON DELETE CASCADE,
    capacity  INT NOT NULL DEFAULT 0 CHECK (capacity >= 0),
    available INT NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved  INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    sold      INT NOT NULL DEFAULT 0 CHECK (sold >= 0),
    withheld  INT NOT NULL DEFAULT 0 CHECK (withheld >= 0),
    version   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_inventory_sum CHECK (available + reserved + sold + withheld = capacity)
);

CREATE TABLE user_tier_quota (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier_id            UUID NOT NULL REFERENCES ticket_tiers(id) ON DELETE CASCADE,
    active_quantity    INT NOT NULL DEFAULT 0 CHECK (active_quantity >= 0),
    confirmed_quantity INT NOT NULL DEFAULT 0 CHECK (confirmed_quantity >= 0),
    version            BIGINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, tier_id)
);

CREATE TABLE bookings (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id),
    event_id               UUID NOT NULL REFERENCES events(id),
    tier_id                UUID NOT NULL REFERENCES ticket_tiers(id),
    quantity               INT NOT NULL CHECK (quantity > 0),
    status                 VARCHAR(40) NOT NULL
        CHECK (status IN ('PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED',
                          'CANCELLED_BEFORE_PAYMENT', 'CANCELLATION_PENDING', 'CANCELLED')),
    entitlement_status     VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (entitlement_status IN ('NONE', 'ACTIVE', 'REVOKED', 'CONSUMED')),
    refund_state           VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (refund_state IN ('NONE', 'PENDING', 'REFUNDED', 'REFUND_FAILED', 'MANUAL_REVIEW')),
    unit_price_minor       BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    currency               CHAR(3) NOT NULL,
    active_payment_intent_id UUID,
    expires_at             TIMESTAMPTZ,
    confirmed_at           TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    price_snapshot         JSONB NOT NULL,
    policy_snapshot        JSONB NOT NULL,
    version                BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_bookings_user ON bookings (user_id, created_at DESC);
-- Low-frequency compensation scan for the expiry scheduler.
CREATE INDEX ix_bookings_expiry ON bookings (expires_at) WHERE status = 'PAYMENT_PENDING';
CREATE INDEX ix_bookings_tier ON bookings (tier_id);

CREATE TABLE reservations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    tier_id    UUID NOT NULL REFERENCES ticket_tiers(id),
    quantity   INT NOT NULL CHECK (quantity > 0),
    status     VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'WITHHELD')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reservations_tier_status ON reservations (tier_id, status);

-- Request fingerprint idempotency. Key digest is HMAC-SHA-256 of the raw
-- client key; uniqueness scope is actor + endpoint scope + key digest.
CREATE TABLE idempotency_records (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor        UUID NOT NULL,
    scope        VARCHAR(100) NOT NULL,
    key_digest   CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state        VARCHAR(20) NOT NULL
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    status_code  INT,
    response     JSONB,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (actor, scope, key_digest)
);
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);
