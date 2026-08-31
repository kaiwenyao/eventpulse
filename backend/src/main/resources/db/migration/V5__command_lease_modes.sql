-- Distinguish an external action lease from an UNKNOWN status-query lease.
-- Without this marker, an expired status-query lease could be reclaimed by the
-- normal dispatcher and incorrectly replay CAPTURE/VOID/REFUND.
ALTER TABLE commands
    ADD COLUMN lease_mode VARCHAR(20) NOT NULL DEFAULT 'EXECUTE'
        CHECK (lease_mode IN ('EXECUTE', 'QUERY'));
ALTER TABLE commands ADD COLUMN lease_acquired_at TIMESTAMPTZ;

-- Commands already waiting for resolution must never be treated as actions
-- during a rolling deployment.
UPDATE commands SET lease_mode = 'QUERY' WHERE state = 'UNKNOWN_QUERY';

CREATE INDEX ix_commands_query_lease ON commands (lease_until)
    WHERE state = 'UNKNOWN_QUERY' OR (state = 'RUNNING' AND lease_mode = 'QUERY');
