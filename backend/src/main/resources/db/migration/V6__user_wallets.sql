-- User wallet for in-transaction debit/credit. Payment no longer leaves the
-- booking transaction for a simulated gateway, so gateway_results goes away.
-- refunds.command_id is nullable: refunds complete in the same transaction
-- as cancellation and no longer require a durable REFUND command.

CREATE TABLE user_wallets (
    user_id                 UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    currency                CHAR(3) NOT NULL,
    available_amount_minor  BIGINT NOT NULL DEFAULT 0 CHECK (available_amount_minor >= 0),
    version                 BIGINT NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE refunds ALTER COLUMN command_id DROP NOT NULL;

DROP TABLE IF EXISTS gateway_results;
