# Changelog

All notable changes are recorded here. Format: [Keep a Changelog](https://keepachangelog.com/), versioning: [SemVer](https://semver.org/). Commits follow Conventional Commits.

## [Unreleased]

### Added

- App release versioning: one version in the root `VERSION` file, kept equal in `backend/pom.xml` and `frontend/package.json` (CI check); build stamps with version, 7-character commit and UTC build time (untagged builds are `X.Y.Z-dev+commit`, tag `vX.Y.Z` builds exactly `X.Y.Z`); public `GET /api/v1/version`; version in the login card footer and the Help popover, with `(server v<x>)` when frontend and server differ (SETUP-11).
- Login, users and roles, app shell: `security` and `audit` tables (V2), HTTP Basic against `security.user_account` with bcrypt cost 12, roles VIEWER/EDITOR/PUBLISHER/ADMIN, `GET /api/v1/me`, first-admin bootstrap, demo users and `GET /api/v1/auth/demo-users` (local/dev only), account lockout, auth events; login page with demo user selector and version footer; two-row app shell with Monitor/Design switch and right panel (SETUP-12).
- Database baseline: Flyway V1 (PostGIS, schemas `ref`, `layout`, `audit`, `security`), `castor_flyway` and `castor_app` roles with an append-only audit schema, Hibernate `validate` (SETUP-07).
- Backend skeleton: Maven multi-module build, `castor-core` Quarkus service with health, OpenAPI, metrics, JSON logging, hexagonal packages with ArchUnit rules, Problem Details errors and HTTP hardening (SETUP-04).
- Repository layout, ignore rules, editor config, CODEOWNERS, PR template and Dependabot config (SETUP-02).
- Phase 1 scope, rules and definition of done (SETUP-01).