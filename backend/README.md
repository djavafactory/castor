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

Requires Java 25 and Docker (Dev Services starts PostGIS). The commands below apply once SETUP-04 adds the Maven project.

```
./mvnw quarkus:dev          # dev mode
./mvnw verify               # unit + integration tests, coverage
./mvnw spotless:apply       # format
```