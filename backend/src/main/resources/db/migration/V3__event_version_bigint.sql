-- Hibernate maps @Version long to BIGINT; V2 created INTEGER.
ALTER TABLE events ALTER COLUMN version TYPE BIGINT;
