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
| Kafka image | `apache/kafka:4.2.2` (compose, Dev Services provider `upstream-kafka` and the outage-test Testcontainers broker must use it); kafka-clients 4.2.1 from the BOM |
| Event schema validator | networknt `json-schema-validator` 2.0.8 (declared outside the BOM; 3.x needs Jackson 3) |

Full list: root [README](../README.md#technology-stack).

## Package layout

`com.djavafactory.castor.<service>.<bounded-context>.<layer>`

- service: `castor-core`; contexts: `port`, `layout`, `geometry`, `identity`, `audit`, `shared`.
- `castor-contracts` is a plain-Java module (no Quarkus) shared by every service: `com.djavafactory.castor.contracts` holds the canonical event envelope, the JSON Schemas of schema version 1 (`schemas/v1/`), `Unit`, `Topics`, `CanonicalEventValidator`, `CanonicalEventCodec` and `TopicProvisioner`. See README "Decisions" (INGEST-01) for the compatibility policy.
- layers: `api` (REST, DTOs) -> `application` (use cases) -> `domain` (pure Java, no framework imports) <- `infrastructure` (Hibernate, security, file import).

Errors use RFC 9457 Problem Details (`application/problem+json`). API base path `/api/v1`.

## Run and test

Requires Java 25 and Docker running. Nothing else: no database to install, no schema or data to load. Dev Services starts a throwaway `postgis/postgis:18-3.6` container (Testcontainers) for `quarkus:dev` and for every test, and Flyway applies all migrations to it at start-up. Everyone who runs the commands below gets the same database. The first run pulls the image and is slower.

Dev Services also starts one throwaway `apache/kafka:4.2.2` broker (one per test JVM, `quarkus.kafka.devservices.shared=false` in tests). No test connects to the local or dev broker; only a test that must stop or restart the broker (`KafkaHealthIT`, later OPS-03) starts its own Testcontainers broker through `RestartableKafkaResource`.

Reference data and later seed data must also come from migrations (or test fixtures), never from a manual import, so this stays true.

```
./mvnw -pl castor-core quarkus:dev                     # run locally
./mvnw -B spotless:check verify                              # format, unit (*Test), integration (*IT), coverage
./mvnw spotless:apply                                        # format
```

Which database each kind of run uses:

| Run | Database | Roles |
| --- | --- | --- |
| `quarkus:dev` and every `@QuarkusTest` (tests run in profile `test`, which inherits `dev`) | Dev Services container, started and removed automatically | one superuser for the app and Flyway |
| `DatabaseBaselineIT` | its own Testcontainers container, prepared with `db/init/01-roles.sql` | the real `castor_flyway` and `castor_app`, to prove least privilege |

Dev Services data is lost when `quarkus:dev` stops. To keep data between runs use the Docker database of the `local` profile: `docker compose --profile local up -d --wait`, then `./mvnw -pl castor-core quarkus:dev -Dquarkus.profile=local` (see the root README).

Run one test class: `./mvnw -pl castor-core verify "-Dit.test=DatabaseBaselineIT"` (integration) or `./mvnw -pl castor-core test "-Dtest=ConfigurationTest"` (unit).

| What | Where |
| --- | --- |
| API, `/q/openapi`, `/q/swagger-ui` | `:8081` |
| `/q/health/live`, `/q/health/ready`, `/q/metrics` | `:9000` (management, keep it off the public network) |

### Profiles and environment

| Profile | Use | Database | Logs |
| --- | --- | --- | --- |
| `dev` (`quarkus:dev`; profile `test` inherits it for all automated tests) | developer machine | Dev Services, or set `QUARKUS_DATASOURCE_JDBC_URL` | plain text, DEBUG for `com.djavafactory.castor` |
| `local` (`-Dquarkus.profile=local`, inherits `dev`) | developer machine, persistent data | Docker PostGIS on `localhost:${LOCAL_DB_PORT}` (`application-local.properties`) | same as `dev` |
| `prod` (every deployed environment, shared dev included) | deployed | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` for the app; `DB_FLYWAY_USERNAME`, `DB_FLYWAY_PASSWORD` for migrations | JSON |

Tests run in the Quarkus profile `test`, which inherits `dev` (`%test.quarkus.config.profile.parent=dev`); each test JVM gets its own fresh PostGIS container (`TestDatabase.reset()` truncates every table between test classes, Flyway migrates once per JVM). No test connects to the local or dev databases. A test that needs prod behaviour sets it with a `QuarkusTestProfile` (see `SecuredOpenApiProfile`).

In `prod`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_FLYWAY_USERNAME`, `DB_FLYWAY_PASSWORD` and `CASTOR_CORS_ORIGINS` are required. A missing one stops start-up with a message naming the variable. `CASTOR_CORS_ORIGINS` is a comma separated list; `*` is refused.

The environment is named by `CASTOR_ENV` (local, dev or test), independent of the Quarkus profile. The `castor.*` settings are bound in `CastorConfig`. `ConfigGuard` stops start-up for demo login, placeholders, short admin passwords or open CORS when `CASTOR_ENV` is anything else, and refuses `CASTOR_RESET_ON_START=true` outside dev and test. With the flag on, every start drops and recreates the schemas (Flyway clean, enabled only then) and runs the `DataSeedStep` beans.

### Behaviour worth knowing

- At start castor-core creates the four Kafka topics that are missing (`KafkaTopicsStartup`, `TopicProvisioner`; existing topics are never altered or deleted). If the broker does not answer within `castor.kafka.topics-timeout` (30 s) the process exits. After start `/q/health/ready` includes the "Kafka" check (`KafkaReadinessCheck`, 3 s timeout) and goes DOWN while the broker is unreachable; `/q/health/live` does not depend on Kafka.
- Migrations run at start (`quarkus.flyway.migrate-at-start`). If the database is down at start the process exits instead of reporting DOWN. After start, `/q/health/ready` goes DOWN when the database is lost or a migration is pending. `/q/health/live` only reports the JVM.
- Errors are RFC 9457 `application/problem+json`. Each response has an `X-Request-Id` header (a safe client value is reused). Non-500 bodies carry `requestId`; a 500 body carries only `errorId`, and the stack trace is in the log under that id.
- Bodies over 1 MB are refused with 413 (10 MB on `/api/v1/layouts/{id}/import`). Writes accept `application/json` and `application/geo+json` only.
- OpenAPI and Swagger UI need the `ADMIN` role except in `dev`.
- Custom metrics: `castor_validation_duration`, `castor_publish_count`, `castor_import_count`, `castor_spatial_query_duration`. Use the `OperationMetrics` port from use cases.
- Coverage gate is `jacoco.line.minimum` / `jacoco.branch.minimum` in `pom.xml` (0 until SETUP-09).
## Tests and coverage gates

| Type | Naming | Runs in | Tools |
|---|---|---|---|
| Unit (domain rules, geo, mappers) | `*Test` | surefire | JUnit 5 |
| Component | `*Test` with `@QuarkusTest` | surefire | REST Assured, `@TestSecurity` |
| Integration | `*IT` | failsafe | Testcontainers `postgis/postgis:18-3.6`, one container per JVM |
| Architecture | `ArchitectureTest` | surefire | ArchUnit |

`./mvnw verify` runs all of them and fails when JaCoCo (merged from every run) shows less than 85% line coverage in any `*.domain` package or less than 70% overall. Database rules are in `DatabaseRulesIT`; fixture layouts live in `src/test/resources/fixtures/`.
