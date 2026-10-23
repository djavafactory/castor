#!/bin/sh
# Runs once, when the database volume is first created. Creates the application roles (SETUP-07 tightens the grants).
# castor_flyway owns migrations, castor_app is the runtime login. Both use CASTOR_ROLE_PASSWORD (DB_PASSWORD in .env).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v role_password="$CASTOR_ROLE_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE castor_flyway LOGIN PASSWORD :'role_password';
CREATE ROLE castor_app LOGIN PASSWORD :'role_password';
-- Until SETUP-07 defines per-schema grants the app role also migrates, so it may create schemas
GRANT CREATE, CONNECT ON DATABASE :"db" TO castor_flyway, castor_app;
SQL
