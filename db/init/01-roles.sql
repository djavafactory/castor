-- Local/dev bootstrap, run once by the PostGIS image on an empty data directory (mounted by compose.yaml, SETUP-08).
-- Runs as the superuser against POSTGRES_DB. Passwords below are for local/dev only; later environments create the
-- same roles with real secrets (see docs/2-database-baseline.md).
--
--   castor_flyway  owns the schemas, runs migrations only, never used by the running service
--   castor_app     castor-core: DML on its schemas, no DDL, no ownership
--   castor_gateway castor-gateway (INGEST-02): owns schema ingest, runs its own migrations and the service itself; it has
--                  no right outside that schema (no CREATE on the database, none on public)

CREATE EXTENSION IF NOT EXISTS postgis;  -- needs superuser, so it is not left to the migrations

CREATE ROLE castor_flyway LOGIN PASSWORD 'castor_flyway_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE castor_app    LOGIN PASSWORD 'castor_app_dev'    NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE castor_gateway LOGIN PASSWORD 'castor_gateway_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO castor_app', current_database());
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO castor_flyway', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO castor_gateway', current_database());
END
$$;

-- The gateway's own schema (INGEST-02): created here, owned by the role that uses it, so its Flyway history lives in it
-- and a reset (Flyway clean) can empty it without any right on the database.
CREATE SCHEMA ingest AUTHORIZATION castor_gateway;
