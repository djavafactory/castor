# Castor

Reusable port digital twin for conventional cargo ports. The first port is Westports Malaysia (Pulau Indah, Port Klang). The earlier Portwise prototype lives in [`portwise/`](portwise/README.md).

## Prerequisites

- Docker (PostGIS 18-3.6 runs in a container)
- Java 25 (backend)
- Node 24 (frontend)

## Repository layout

```
castor/
  backend/                  Maven multi-module (Quarkus), see backend/README.md
  frontend/                 React + Vite + Three.js, see frontend/README.md
  compose.yaml              local and dev profiles (SETUP-08)
  .env.example              placeholders only (SETUP-08)
  .github/                  ci.yml (SETUP-10), dependabot.yml, CODEOWNERS, pull_request_template.md
  .gitignore  .editorconfig  README.md  CHANGELOG.md  VERSION
```

Each app owns its Dockerfile (`backend/Dockerfile`, `frontend/Dockerfile`). There is no `gateway/` folder in Phase 1; it is added when the Phase 2 gateway work starts.

Data flow (Phase 1): browser frontend <-> backend (REST) -> PostgreSQL + PostGIS. Phase 2 adds Westports systems -> gateway -> Kafka -> backend and a WebSocket to the frontend; see [Target architecture](#target-architecture).

## How to run

Local and dev profiles are defined in `compose.yaml` (SETUP-08):

```
cp .env.example .env
docker compose --profile local up
```

Run the apps individually as described in [backend/README.md](backend/README.md) and [frontend/README.md](frontend/README.md).

## Git rules

Trunk-based with short-lived branches, Conventional Commits, squash merge. `main` is protected: green CI and one review. CODEOWNERS covers `backend/`, `frontend/`, `.github/`.

## Phase 1 scope

> Castor is a reusable port digital twin. The first port is Westports Malaysia (Pulau Indah, Port Klang). This section is the Phase 1 brief and applies to every Phase 1 ticket. The Portwise prototype described in the rest of this README is a separate, earlier codebase.

**Motto:** create the port accurately, connect the operations, explain the bottlenecks, test better decisions.

### Product brief

Phase 1 is "make an accurate replica":

- Backend and frontend foundations (Java 25 + Quarkus, React + Three.js, PostgreSQL 18 + PostGIS 3.6).
- Port Layout Designer: search a place, trace structures on an approved basemap, drop equipment from a palette, rotate and resize in metres, attach operational meaning, preview in 3D, save and publish a versioned layout.
- Geospatially correct assets stored in WGS84.
- A 2D map and a 3D replica that show the same positions.
- A read-only Monitor mode that shows the latest published layout in 3D.

Phase 1 does not connect to any Westports system. It gives every asset, area and road a stable ID so that operational events can attach to them in Phase 2.

### Target architecture

```
Phase 1: browser frontend <-> backend (REST) -> PostgreSQL + PostGIS
Phase 2: Westports systems -> gateway -> Kafka -> backend; backend <-> frontend (REST + WebSocket)
```

Browsers never call the gateway.

### Design for more cargo types

No conventional-cargo-only assumption may be hard-coded into the asset registry, storage model or flow links. Container operations are expected later and are added as data (new asset types, new cargo families), not as code changes.

### Scope rules

1. **In scope:** login, app shell, Design mode (layout designer), Monitor mode (read-only viewer of the published layout), versioned layouts (draft, publish, restore), GeoJSON import/export, accuracy checks.
2. **Out of scope (Phase 2+):** live events, ingest gateway, Kafka, simulator, WebSocket, live positions, vessel calls, KPIs, berth schedule, alerts, flow analytics, Scenario Lab, multi-port UI, OIDC/Keycloak, deployment to replica8.it.
3. Monitor mode shows no live data in Phase 1: no events feed, no ships tab, no KPIs, no live bar, no "Live / Reconnecting" connection chip.
4. The 3D ground is flat (y = 0). No bumps or waves on land, roads or pads. Waves only on water.
5. Coordinates are stored as WGS84 [longitude, latitude] (EPSG:4326). Lengths, areas and distances are computed in metres in EPSG:3380 (GDM2000 / Selangor Grid). The 3D scene uses local metres in the quay frame (SETUP-03). Never compute metres from raw degrees.
6. Google Maps (or any geocoder) may be used to find a place, never as a data source: no tracing over its imagery, no stored geocoding results.
7. Every stored shape carries a source and an accuracy label (`SURVEYED`, `VERIFIED`, `OPERATOR`, `APPROXIMATE`). Synthetic data is always `APPROXIMATE` and the UI shows a SYNTHETIC badge. False accuracy is worse than an honest approximate label.
8. `port_id` on every table and every API path, so more ports can be added later without a schema change.
9. Roles are checked with `@RolesAllowed` in business code, never by looking at the login mechanism.
10. Versioned contracts: REST under `/api/v1`, GeoJSON exports carry a schema version, Flyway migrations.
11. The look and feel follows the frontend design-system tickets (SETUP-05, CORE-01, SETUP-12, CORE-02, CORE-03, SETUP-06, JOIN-03) in every frontend ticket.
12. Ticket text is the specification. Tickets never point to outside documents for required behaviour.

### Definition of done (every ticket)

- Code reviewed and approved by one other developer.
- Unit/integration tests written and green in CI; coverage gates met (SETUP-09).
- Lint and format checks pass (Spotless for Java, ESLint + Prettier for TypeScript).
- No new HIGH or CRITICAL vulnerabilities (Trivy, dependency review).
- UI work follows SETUP-05, CORE-01, SETUP-12, CORE-02, CORE-03, SETUP-06, JOIN-03 and passes keyboard and axe checks.
- README "Decisions" section updated when a decision or behaviour changes.

### Technology stack

Versions recorded 2026-10-23 from ticket SETUP-02 (re-check at kick-off and update this date). Rule: latest stable; where packages conflict, the newest versions that work together. **HELD** = kept back on purpose. Dependabot PRs must not break a HELD pin.

**Frontend (npm)**

- Node.js 24.22.0 LTS; vite 8.3.5; @vitejs/plugin-react 6.1.2
- react, react-dom 19.3.1 (HELD: @react-three/fiber 9.8 needs react < 19.4)
- typescript ~6.0.3 (HELD: typescript-eslint 8.72 needs TypeScript < 6.1)
- tailwindcss, @tailwindcss/vite 4.3.3; zustand 5.0.15; three 0.186.1 / @types/three 0.186.0
- @react-three/fiber 9.8.2; @react-three/drei 10.7.9; @tanstack/react-query 5.104.5; react-router 8.5.0; zod 4.6.7
- shadcn CLI 4.22.1 / radix-ui 1.8.1; lucide-react 1.61.0; class-variance-authority 0.7.1; clsx 2.1.1; tailwind-merge 3.7.0; sonner 2.0.8; @fontsource/be-vietnam-pro 5.3.0; tw-animate-css 1.4.0
- Dev/test: @types/react, @types/react-dom 19.3.0; @types/node ^24 (HELD: match Node 24); eslint 10.13.0 / @eslint/js 10.0.1; typescript-eslint 8.72.0; eslint-plugin-react-hooks 7.1.1; eslint-plugin-react-refresh 0.5.7; globals 17.13.0; prettier 3.9.10; prettier-plugin-tailwindcss 0.8.1; vitest / @vitest/coverage-v8 5.0.5; jsdom 30.1.3; @testing-library/react 16.3.3; @testing-library/dom 10.4.2; @testing-library/jest-dom 7.0.1; @testing-library/user-event 14.6.8; @react-three/test-renderer 9.1.1; msw 3.0.3; @playwright/test 1.64.1; @axe-core/playwright (latest compatible); r3f-perf 7.2.3 (dev)

**Backend (Maven)** - Quarkus 4.0 and Hibernate ORM 8.0 betas are NOT used.

- Java 25 LTS; Quarkus platform BOM io.quarkus.platform:quarkus-bom 3.40.1.1 LTS; Maven 3.10.0 via wrapper
- From the BOM, never overridden: Hibernate ORM + Spatial 7.4.9.Final, Flyway 12.0.0, PostgreSQL JDBC 42.7.13, REST Assured 6.0.1
- Declared outside the BOM (only these): Testcontainers 2.0.5, ArchUnit 1.5.1, JTS IO (jts-io-common) 1.20.0
- Plugins: JaCoCo 0.8.15; Spotless 3.10.4 (Palantir Java Format)
- Quarkus extensions: quarkus-rest-jackson, quarkus-hibernate-orm-panache, hibernate-spatial, quarkus-jdbc-postgresql, quarkus-flyway, quarkus-hibernate-validator, quarkus-security-jpa, quarkus-scheduler, quarkus-smallrye-health, quarkus-smallrye-openapi, quarkus-micrometer-registry-prometheus, quarkus-logging-json
- Test: quarkus-junit5, quarkus-test-security, quarkus-jacoco

**Docker images** (exact tags, never `latest`)

- postgis/postgis:18-3.6 (also for Quarkus Dev Services and Testcontainers; the default image has no PostGIS)
- eclipse-temurin:25-jdk-noble (build) / eclipse-temurin:25-jre-noble (run)
- node:24.22.0-trixie-slim (build) / nginxinc/nginx-unprivileged:1.31.7-alpine (run)

**GitHub Actions**

actions/checkout v7, actions/setup-java v6, actions/setup-node v7, actions/upload-artifact v7, docker/setup-buildx-action v4, docker/build-push-action v7, aquasecurity/trivy-action v0.36.0, actions/dependency-review-action v5.

**Not used in Phase 1:** Kafka, Kafka UI, WebSocket libraries, REST client, fault tolerance.

### Conventions

- Backend packages: `it.replica8.castor.<service>.<bounded-context>.<layer>` (see [backend/README.md](backend/README.md)).
- Frontend folders and the no-cross-feature-import rule: see [frontend/README.md](frontend/README.md).
- Errors: RFC 9457 Problem Details (`application/problem+json`). API base path `/api/v1`.
- Not committed: `docs/`, `mockup/`, `castor project realtime 3d port for.txt`, `.env`, build and test output, IDE files.

### Decisions

Record decisions and behaviour changes here, newest first.

- _No decisions recorded yet._

### Credits and origins

- The `portwise/` folder holds the earlier Portwise 3D port prototype (see its README); Castor builds on its ideas but is a separate codebase.
- Built by Djava Factory for the Westports Malaysia replica; map data attribution and licences are recorded here as sources are added.
