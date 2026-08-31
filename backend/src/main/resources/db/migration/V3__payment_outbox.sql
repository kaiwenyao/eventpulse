-- Payment single-flight, payment balance, durable commands, gapless outbox
-- and consumer cursors.

CREATE TABLE payment_intents (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id           UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    attempt_no           INT NOT NULL,
    state                VARCHAR(30) NOT NULL
        CHECK (state IN ('CREATED', 'CAPTURE_SUBMITTED', 'SUCCEEDED', 'FAILED',
                         'CANCELED', 'VOIDED', 'UNKNOWN')),
    requested_amount_minor BIGINT NOT NULL CHECK (requested_amount_minor >= 0),
    captured_amount_minor  BIGINT NOT NULL DEFAULT 0 CHECK (captured_amount_minor >= 0),
    currency             CHAR(3) NOT NULL,
    provider_key         VARCHAR(80) NOT NULL UNIQUE,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Exactly one active payment intent per booking (single flight).
CREATE UNIQUE INDEX ux_payment_intents_active ON payment_intents (booking_id) WHERE active = TRUE;
CREATE INDEX ix_intents_booking ON payment_intents (booking_id);

-- The three money amounts for one booking live on one row so the refund
-- reservation invariant is a same-row CHECK.
CREATE TABLE payment_balance (
    booking_id                UUID PRIMARY KEY REFERENCES bookings(id) ON DELETE CASCADE,
    currency                  CHAR(3) NOT NULL,
    captured_amount_minor     BIGINT NOT NULL DEFAULT 0 CHECK (captured_amount_minor >= 0),
    refund_reserved_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (refund_reserved_amount_minor >= 0),
    refunded_amount_minor     BIGINT NOT NULL DEFAULT 0 CHECK (refunded_amount_minor >= 0),
    version                   BIGINT NOT NULL DEFAULT 0,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_refund_within_captured
        CHECK (refund_reserved_amount_minor + refunded_amount_minor <= captured_amount_minor)
);

CREATE TABLE refunds (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    UUID NOT NULL REFERENCES payment_intents(id),
    booking_id    UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    amount_minor  BIGINT NOT NULL CHECK (amount_minor > 0),
    state         VARCHAR(20) NOT NULL
        CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW', 'ABANDONED')),
    command_id    UUID NOT NULL UNIQUE,
    provider_ref  VARCHAR(80) UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refunds_booking ON refunds (booking_id);

-- Durable commands: business transactions only INSERT commands; a dispatcher
-- claims them under a lease and executes the external call OUTSIDE any tx.
CREATE TABLE commands (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind            VARCHAR(20) NOT NULL
        CHECK (kind IN ('CAPTURE', 'VOID', 'REFUND', 'NOTIFY')),
    aggregate_type  VARCHAR(40) NOT NULL,
    aggregate_id    UUID NOT NULL,
    provider_key    VARCHAR(80) NOT NULL UNIQUE,
    target_provider_key VARCHAR(80),
    state           VARCHAR(30) NOT NULL DEFAULT 'READY'
        CHECK (state IN ('READY', 'RUNNING', 'UNKNOWN_QUERY', 'MANUAL_REVIEW', 'COMPLETED', 'CANCELED')),
    lease_owner     VARCHAR(80),
    lease_until     TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 8,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    result          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX ix_commands_claim ON commands (next_attempt_at)
    WHERE state IN ('READY', 'UNKNOWN_QUERY');
CREATE INDEX ix_commands_lease_expiry ON commands (lease_until) WHERE state = 'RUNNING';
CREATE INDEX ix_commands_aggregate ON commands (aggregate_type, aggregate_id);

CREATE TABLE command_attempts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_id UUID NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    attempt_no INT NOT NULL,
    outcome    VARCHAR(20) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'UNKNOWN', 'EXCEPTION')),
    detail     JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_command_attempts_command ON command_attempts (command_id, attempt_no);

-- State for the isolated payment gateway simulator (demo profile).
CREATE TABLE gateway_results (
    provider_key VARCHAR(80) PRIMARY KEY,
    kind         VARCHAR(20) NOT NULL,
    amount_minor BIGINT NOT NULL,
    scenario     VARCHAR(40) NOT NULL,
    status       VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    available_at TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Gapless per-aggregate sequence allocation; never a DB sequence.
CREATE TABLE aggregate_counters (
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   UUID NOT NULL,
    next_sequence  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (aggregate_type, aggregate_id)
);

CREATE TABLE outbox (
    event_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   UUID NOT NULL,
    sequence       BIGINT NOT NULL,
    topic          VARCHAR(80) NOT NULL,
    event_type     VARCHAR(80) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    correlation_id UUID,
    causation_id   UUID,
    trace_id       VARCHAR(64),
    payload        JSONB NOT NULL,
    state          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'PUBLISHED')),
    attempts       INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    UNIQUE (aggregate_type, aggregate_id, sequence)
);
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE state = 'PENDING';

CREATE TABLE consumer_cursors (
    consumer       VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   UUID NOT NULL,
    last_sequence  BIGINT NOT NULL DEFAULT 0,
    last_event_id  UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);

CREATE TABLE consumer_gaps (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer       VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   UUID NOT NULL,
    expected       BIGINT NOT NULL,
    received       BIGINT NOT NULL,
    event_id       UUID,
    state          VARCHAR(30) NOT NULL DEFAULT 'OPEN'
        CHECK (state IN ('OPEN', 'RESOLVED_REPLAY', 'RESOLVED_SKIP', 'RESOLVED_REBUILD')),
    resolution_note TEXT,
    approved_by     VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    UNIQUE (consumer, aggregate_type, aggregate_id, expected)
);

CREATE TABLE tickets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    sequence   INT NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
    token_hash CHAR(64) NOT NULL,
    used_at    TIMESTAMPTZ,
    used_by    UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (booking_id, sequence),
    UNIQUE (token_hash)
);
CREATE INDEX ix_tickets_booking ON tickets (booking_id);
