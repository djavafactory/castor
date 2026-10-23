-- Local/dev bootstrap, run once by the PostGIS image on an empty data directory (mounted by compose.yaml, SETUP-08).
-- Runs as the superuser against POSTGRES_DB. Passwords below are for local/dev only; later environments create the
-- same two roles with real secrets (see docs/2-database-baseline.md).
--
--   castor_flyway  owns the schemas, runs migrations only, never used by the running service
--   castor_app     the service: DML on its schemas, no DDL, no ownership

CREATE EXTENSION IF NOT EXISTS postgis;  -- needs superuser, so it is not left to the migrations

CREATE ROLE castor_flyway LOGIN PASSWORD 'castor_flyway_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE castor_app    LOGIN PASSWORD 'castor_app_dev'    NOSUPERUSER NOCREATEDB NOCREATEROLE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO castor_app', current_database());
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO castor_flyway', current_database());
END
$$;
