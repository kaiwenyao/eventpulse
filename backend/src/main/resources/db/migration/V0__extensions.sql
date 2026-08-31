-- Extensions. pgvector is optional (used by recommendation V1); the column is
-- added conditionally in V4 so environments without the extension still migrate.
CREATE EXTENSION IF NOT EXISTS postgis;

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector not available; recommendation V1 vector column skipped';
END $$;
