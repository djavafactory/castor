-- Baseline: extension, schemas and the rights of the application role. Tables arrive in V2 and later.
-- Runs as castor_flyway, which therefore owns every object created here and later.

-- Already present when the init script ran; created here only on a database prepared without it (needs superuser).
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS ref;
CREATE SCHEMA IF NOT EXISTS layout;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS security;

-- castor_app gets data rights on whatever castor_flyway creates later, so a new table needs no grant in its migration.
-- Skipped when the role does not exist (Dev Services and tests connect as a single superuser).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'castor_app') THEN
        RETURN;
    END IF;

    GRANT USAGE ON SCHEMA ref, layout, audit, security TO castor_app;

    ALTER DEFAULT PRIVILEGES IN SCHEMA ref, layout, security
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO castor_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA ref, layout, security
        GRANT USAGE, SELECT ON SEQUENCES TO castor_app;

    -- The audit trail is append-only: tables created in audit never carry UPDATE or DELETE for the application.
    ALTER DEFAULT PRIVILEGES IN SCHEMA audit
        GRANT SELECT, INSERT ON TABLES TO castor_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA audit
        GRANT USAGE, SELECT ON SEQUENCES TO castor_app;
END
$$;
