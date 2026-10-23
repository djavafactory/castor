# Changelog

All notable changes are recorded here. Format: [Keep a Changelog](https://keepachangelog.com/), versioning: [SemVer](https://semver.org/). Commits follow Conventional Commits.

## [Unreleased]

### Added

- Database baseline: Flyway V1 (PostGIS, schemas `ref`, `layout`, `audit`, `security`), `castor_flyway` and `castor_app` roles with an append-only audit schema, Hibernate `validate` (SETUP-07).
- Backend skeleton: Maven multi-module build, `castor-core` Quarkus service with health, OpenAPI, metrics, JSON logging, hexagonal packages with ArchUnit rules, Problem Details errors and HTTP hardening (SETUP-04).
- Repository layout, ignore rules, editor config, CODEOWNERS, PR template and Dependabot config (SETUP-02).
- Phase 1 scope, rules and definition of done (SETUP-01).