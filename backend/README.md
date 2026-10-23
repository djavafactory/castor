# Castor backend

Quarkus (Java 25) REST service `castor-core`: layouts, geometry, identity, audit. Maven multi-module, created in SETUP-04.

## Stack and versions

| Item | Version |
| --- | --- |
| Java | 25 LTS |
| Quarkus platform BOM | `io.quarkus.platform:quarkus-bom` 3.40.1.1 LTS (Quarkus 4.0 betas not used) |
| Maven | 3.10.0 via wrapper (`./mvnw`) |
| Hibernate ORM + Spatial, Flyway, PostgreSQL JDBC, REST Assured | from the BOM, never overridden |
| Testcontainers / ArchUnit / JTS IO | 2.0.5 / 1.5.1 / 1.20.0 (the only versions declared outside the BOM) |
| JaCoCo / Spotless plugin | 0.8.15 / 3.10.4 (Palantir Java Format) |
| Database image | `postgis/postgis:18-3.6` (Dev Services and Testcontainers must use it) |

Full list: root [README](../README.md#technology-stack).

## Package layout

`it.replica8.castor.<service>.<bounded-context>.<layer>`

- service: `castor-core`; contexts: `port`, `layout`, `geometry`, `identity`, `audit`, `shared`.
- layers: `api` (REST, DTOs) -> `application` (use cases) -> `domain` (pure Java, no framework imports) <- `infrastructure` (Hibernate, security, file import).

Errors use RFC 9457 Problem Details (`application/problem+json`). API base path `/api/v1`.

## Run and test

Requires Java 25 and Docker (Dev Services starts PostGIS, no manual database setup).

```
./mvnw -pl castor-core quarkus:dev -Dquarkus.profile=local   # run locally
./mvnw -B spotless:check verify                              # format, unit (*Test), integration (*IT), coverage
./mvnw spotless:apply                                        # format
```

| What | Where |
| --- | --- |
| API, `/q/openapi`, `/q/swagger-ui` | `:8081` |
| `/q/health/live`, `/q/health/ready`, `/q/metrics` | `:9000` (management, keep it off the public network) |

### Profiles and environment

| Profile | Use | Database | Logs |
| --- | --- | --- | --- |
| `local` (`dev` inherits it) | developer machine | Docker PostGIS from `docker compose --profile local up -d` (`application-local.properties`, needs `DB_PASSWORD`) | plain text, DEBUG for `it.replica8.castor` |
| `dev-env` | shared dev environment | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | JSON |
| `test` | automated tests | Dev Services | plain text |
| `prod` | placeholder, finished later | same variables as `dev-env` | JSON |

`CASTOR_ENV` (local, dev or test) and the `castor.*` settings are bound in `CastorConfig`; `ConfigGuard` refuses unsafe values and reset-on-start outside dev/test (see the root README). Outside local and test, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `CASTOR_CORS_ORIGINS` are required. A missing one stops start-up with a message naming the variable. `CASTOR_CORS_ORIGINS` is a comma separated list; `*` is refused.

### Behaviour worth knowing

- Migrations run at start (`quarkus.flyway.migrate-at-start`). If the database is down at start the process exits instead of reporting DOWN. After start, `/q/health/ready` goes DOWN when the database is lost or a migration is pending. `/q/health/live` only reports the JVM.
- Errors are RFC 9457 `application/problem+json`. Each response has an `X-Request-Id` header (a safe client value is reused). Non-500 bodies carry `requestId`; a 500 body carries only `errorId`, and the stack trace is in the log under that id.
- Bodies over 1 MB are refused with 413 (10 MB on `/api/v1/layouts/{id}/import`). Writes accept `application/json` and `application/geo+json` only.
- OpenAPI and Swagger UI need the `ADMIN` role except in `local`, `dev` and `dev-env`.
- Custom metrics: `castor_validation_duration`, `castor_publish_count`, `castor_import_count`, `castor_spatial_query_duration`. Use the `OperationMetrics` port from use cases.
- Coverage gate is `jacoco.line.minimum` / `jacoco.branch.minimum` in `pom.xml` (0 until SETUP-09).