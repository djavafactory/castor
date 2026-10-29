# Castor

Reusable port digital twin for conventional cargo ports. The first port is Westports Malaysia (Pulau Indah, Port Klang). The earlier Portwise prototype lives in [`portwise/`](portwise/README.md).

## What Castor is

Castor is a reusable digital twin for conventional cargo ports: a 3D replica of the port (berths, yards, warehouses, cranes, roads) that is built, checked and published in a browser-based layout designer, and shown in a read-only Monitor view. The first port is Westports Malaysia (Pulau Indah, Port Klang). Phase 1 is the designer and the replica, with no live data: see [Phase 1 scope](#phase-1-scope).

## Prerequisites

- Docker (PostGIS 18-3.6 runs in a container)
- Java 25 (backend)
- Node 24 (frontend)

## Repository layout

```
castor/
  backend/                  Maven multi-module (Quarkus), see backend/README.md
  frontend/                 React + Vite + Three.js, see frontend/README.md
  fixtures/                 coordinates.json shared by backend and frontend coordinate tests (CORE-01)
  scripts/                  generate-coordinate-fixtures.mjs
  compose.yaml              local database (SETUP-08)
  .env.example              placeholders only (SETUP-08)
  .github/                  ci.yml (SETUP-10), dependabot.yml, CODEOWNERS, pull_request_template.md
  .gitignore  .editorconfig  README.md  CHANGELOG.md  VERSION
```

Each app owns its Dockerfile (`backend/Dockerfile`, `frontend/Dockerfile`). There is no `gateway/` folder in Phase 1; it is added when the Phase 2 gateway work starts.

Data flow (Phase 1): browser frontend <-> backend (REST) -> PostgreSQL + PostGIS. Phase 2 adds Westports systems -> gateway -> Kafka -> backend and a WebSocket to the frontend; see [Target architecture](#target-architecture).

## Run locally

### New to the project: no manual setup

Install Docker, Java 25 and Node 24, then run these. There is no database to install, no schema to load and no data to import by hand: the backend starts its own PostGIS container (Quarkus Dev Services, Testcontainers underneath) and Flyway builds the whole schema from `backend/castor-core/src/main/resources/db/migration`. Every developer therefore gets the same database.

```
# terminal 1: backend on :8081 (health and metrics on :9000)
cd backend && ./mvnw -pl castor-core quarkus:dev

# terminal 2: frontend on :5173
cd frontend && cp .env.example .env.local && npm ci && npm run dev
```

Run all checks the same way on any machine (this is what CI will run):

```
cd backend  && ./mvnw -B spotless:check verify    # unit + integration tests on a throwaway PostGIS container
cd frontend && npm ci && npm run lint && npm run typecheck && npm test
```

Details and limits: [backend/README.md](backend/README.md#run-and-test) and [frontend/README.md](frontend/README.md#run-and-test). For a long-lived local database with the real `castor_app` and `castor_flyway` roles from `db/init/01-roles.sql`, use the Docker database below.

There is no gateway in Phase 1 (see above), so it has no run instructions yet; it gets its own README when Phase 2 starts.

### With a persistent Docker database

The database runs in Docker; backend and frontend run on your machine with hot reload. Data survives restarts.

```
cp .env.example .env                       # then replace every CHANGE_ME value
docker compose --profile local up -d --wait   # PostGIS only, 127.0.0.1:${LOCAL_DB_PORT:-5432}
cd backend && ./mvnw -pl castor-core quarkus:dev -Dquarkus.profile=local   # local = dev settings + the Docker database
cd frontend && cp .env.example .env.local && npm ci && npm run dev
```

| What | URL |
| --- | --- |
| Frontend | http://localhost:5173 |
| API, Swagger UI | http://localhost:8081, http://localhost:8081/q/swagger-ui |
| Health (management port) | http://localhost:9000/q/health/ready |

Reset local to the seeded layout (wipes the local database volume only):

```
docker compose --profile local down -v && docker compose --profile local up -d
```

## Run the dev environment

The whole built app in containers (`castor-dev-db`, `castor-dev-backend`, `castor-dev-frontend`). Only the frontend port is published; the database and `/q/*` management endpoints are not reachable from the host. Runs side by side with local.

```
docker compose --profile dev up -d --build --wait   # app at http://localhost:${DEV_WEB_PORT:-8080}
docker compose --profile dev down
```

Every backend start in dev wipes and reseeds the data (`CASTOR_RESET_ON_START=true`, set by `compose.yaml`).

## Tests

| What | Command | Needs |
| --- | --- | --- |
| Backend unit, ArchUnit and integration tests (throwaway PostGIS) | `cd backend && ./mvnw -B spotless:check verify` | Docker, Java 25 |
| Frontend lint, types, unit tests | `cd frontend && npm ci && npm run lint && npm run typecheck && npm test` | Node 24 |
| Bundle size budget | `cd frontend && npm run build && npm run bundle:check` | Node 24 |
| End to end, mocked API (login, design system) | `cd frontend && npx playwright install chromium && npm run test:e2e` | Node 24 |
| End to end on the dev stack (everything else, incl. the PRD 19.1 acceptance suite, the demo and the performance budget) | `cd frontend && E2E_BASE_URL=http://localhost:8080 E2E_API_URL=http://localhost:8080/api npm run test:e2e` | the running dev stack |

The acceptance suite is `frontend/e2e/acceptance/`: one spec per PRD 19.1 item (`01` search, basemap and polygon; `03` crane ghost, heading, resize, no drift; `04` drag in 2D; `05` GeoJSON fixtures round trip; `06` measure against the control points; `07` NO SURVEY badge; `08` publish, audit and restore; `09` undo/redo and roles), `demo-blank-canvas.spec.ts` (the Phase 1 finish line, see [Demo](#demo)) and `performance.spec.ts` (the API budgets, see [Performance](#performance)). They sign in with the demo users (password = user name), drive the real screens, and use the API only to prepare and tidy. Each spec leaves the shared draft as it found it; the demo and the publish spec add published versions, which is why the stack resets on start. Use one worker: the specs share the stack.

The e2e browser runs without WebGL (`playwright.config.ts`): no test draws the 3D scene, which shows its "3D view not available" notice, so the suite stays fast. The 3D scene is covered by the unit tests (`@react-three/test-renderer`) and by hand.

## Environment configuration

Each environment is a set of variables; see `.env.example` and `frontend/.env.example`. Secrets never go to git (`.env` and `.env.local` are ignored). `VITE_*` values are bundled into the browser: no secrets there.

- `CASTOR_ENV` is `local`, `dev` or `test`. Demo login (`CASTOR_DEMO_LOGIN`, `VITE_DEMO_LOGIN`) defaults to off and works only when set to true in local or dev.
- The backend refuses to start when `CASTOR_ENV` is anything else and demo login is on, a value contains `CHANGE_ME`, the admin password has fewer than 14 characters, or CORS origins are empty or `*`.
- `CASTOR_RESET_ON_START=true` is allowed only in dev or test, otherwise start-up fails with "Refusing to wipe data: reset-on-start is only allowed in dev or test".

## Data

Reference data comes from migrations, never from a manual import: `V3__ref.sql` (port, data sources, accuracy classes, catalogue, control points) and `V4__asset_types.sql` (asset types). A new asset type is one migration (an `INSERT` into `layout.asset_type`, with `model_key` and a hint) plus a 3D model; nothing else changes. Type specific defaults without a column (reach, tiers, rate) go in the `params` JSON column.

The OpenStreetMap background is the file `backend/castor-core/src/main/resources/seed/westports/osm-reference.geojson` (ODbL, attribution kept). `OsmImportJob` loads it into `ref.reference_feature` in one `ref.import_batch` at start and after a dev reset, upserting by `(port_id, osm_ref)`; an unchanged file (same SHA-256) is skipped. Set `CASTOR_REFERENCE_IMPORT_ON_START=false` to switch it off. A source with `may_persist = false` (GOOGLE) is refused.

Refresh procedure, run only when the data needs updating:

1. Download a dated Geofabrik Malaysia extract; record its date and SHA-256 in the file header (`castor.provenance`).
2. Clip: `osmium extract --bbox 101.26,2.90,101.36,3.02 malaysia.osm.pbf -o westports.osm.pbf`.
3. Export: `osmium export westports.osm.pbf -o westports.geojson`.
4. Convert to the committed format (`FeatureCollection` with a `castor` header `{schemaVersion 1, port, kind "reference-features", attribution, provenance}`; per feature `kind`, `osmRef`, `name`, `heightM`/`widthM`, `source` OSM, `accuracy` APPROXIMATE):

| OSM tag | kind | rule |
|---|---|---|
| `building=*` | BUILDING | height = `height` tag, else `building:levels` x 3.5 m, else 12 m (industrial, warehouse) or 9 m (other) |
| `highway=*` | ROAD | width = `width` tag, else `lanes` x 3.5 m, else primary 10 m, secondary 8 m, tertiary 7 m, service 6 m, other 6 m |
| `railway=rail` | RAIL | |
| `natural=coastline` | COASTLINE | |
| `man_made=quay\|pier\|jetty` | JETTY | |
| `landuse=industrial\|port` | LANDUSE | |
| `natural=water` | WATER | |

5. Commit the file and restart the backend; the batch row in `ref.import_batch` shows the row count and checksum.

## Versioning and releases

One version for the whole app, `MAJOR.MINOR.PATCH`, in the root `VERSION` file (0.1.0 now, 1.0.0 when Phase 1 is accepted). `backend/pom.xml` (`<revision>`) and `frontend/package.json` must equal it; CI runs `node scripts/check-versions.mjs` and fails if they differ.

- Build stamps: version, 7-character git commit, UTC build time. Untagged builds are `<version>-dev+<commit>`; a git tag `vX.Y.Z` builds exactly `X.Y.Z` (CI refuses a tag that differs from `VERSION`). `scripts/build-stamp.sh` prints them. Docker and Compose builds take them as `BUILD_VERSION`, `GIT_COMMIT` and `BUILD_TIME`: `eval "$(scripts/build-stamp.sh)" && export BUILD_VERSION GIT_COMMIT BUILD_TIME && docker compose --profile dev up -d --build`.
- Shown in `GET /api/v1/version` (no login), the login card footer and the Help popover. Local and dev add the commit and environment. A frontend and server on different versions show `(server v<x>)`.
- Release PR: bump `VERSION`, `backend/pom.xml`, `frontend/package.json` (and its lock file) and add the version's section to `CHANGELOG.md`, written from the Conventional Commits since the last tag, all in the same PR. After merge, tag `vX.Y.Z`: CI builds images tagged `X.Y.Z` (not pushed).

## Demo

The product owner's walk through Phase 1: a port built from a blank canvas and published. The same steps run automatically in `frontend/e2e/acceptance/demo-blank-canvas.spec.ts`.

Start the dev stack (`docker compose --profile dev up -d --build --wait`), open http://localhost:8080 and:

1. Sign in as `admin` (choose it in "Choose a demo user"). The 3D port loads in Monitor.
2. **Design**, view **2D**, tab **Versions**, **New blank layout**, confirm "Start blank". The draft has 0 assets.
3. Search `Westports` and press Enter: the map centres on the port.
4. Draw the site: palette tile **Terminal boundary** (click once on the map), **Warehouse (polygon)** (click six or more corners, Enter), **Laydown yard** (click the corners, Enter). Keep clear of the draw panel at the top left of the map.
5. Place two **Berth** tiles on the quay edge (the dashed line), a **Quay crane (rail)** next to a berth, a **Mobile harbour crane** and a **Silo** on the land.
6. Draw an **Exclusion zone**; in the Inspector, "Allowed types inside", tick **Liquid tank**. Place a **Liquid tank** inside it.
7. The deliberate mistake: draw a second exclusion zone over the silo. Tab **Issues** shows "Inside safety exclusion zone ..." as an error and Publish is blocked. Pick the row and drag the silo out of the zone; **Check again** shows 0 errors.
8. **More** (the three dots) -> **Verify accuracy**: the Accuracy check section lists every control point with its residual (all within 0.1 m).
9. Tab **Versions**, write a publish note, **Publish**, confirm. Switch to **Monitor**: the header reads "Published vN ... by admin" and the new layout is in 3D.
10. Click the user name -> **Sign out**. Nothing is left in browser storage but the panel layout.

Two things differ from a literal reading of the ticket: the terminal boundary is a placed rectangle (400 x 200 m, resized in the Inspector) because its catalogue type is a placed type, and the deliberate problem is an exclusion zone drawn over an existing asset, because the designer refuses to save an overlapping asset in the first place.

## Performance

Budgets of JOIN-04 for the target laptop (integrated GPU, 1920 x 1080), and what was measured. `frontend/e2e/acceptance/performance.spec.ts` measures the API budgets and writes `frontend/test-results/performance.json` (uploaded by CI); `npm run bundle:check` measures the bundle. The e2e suite draws no 3D (no WebGL in its browser), so the 3D budgets are checked by hand on the target laptop. Run on the dev stack on 29 Oct 2026 (Windows 11 laptop, Docker Desktop, headless Chromium).

| Budget | Measured | Result |
| --- | --- | --- |
| 60 fps at Overview, all layers, about 30,000 static containers | not measured by the e2e suite | check by hand on the target laptop (PERF-03) |
| First meaningful 3D frame under 4 s, warm cache | not measured by the e2e suite | check by hand on the target laptop (PERF-03) |
| Memory flat over 30 minutes of orbiting | not measured by the e2e suite | check by hand on the target laptop (PERF-02) |
| Frontend bundle without three under 250 KB gzipped | **293.3 KB** (three chunk 242.6 KB, not counted) | **fails by 43 KB**: PERF-01 |
| Published layout API under 300 ms p95 | 25 ms p95 (50 full requests, 288 KB body, 38 KB gzipped) | met |
| Single asset write under 150 ms p95 | 20 ms p95 (30 PATCH requests) | met |
| Reference features under 2 MB | 0.26 MB (277,629 bytes) | met |
| Full layout under 400 draw calls | not measured by the e2e suite | check by hand on the target laptop (PERF-03) |

Failures, to be raised as tickets:

- **PERF-01** Frontend bundle is 293.3 KB gzipped without three, 43 KB over the 250 KB budget (`app-shell` 94.7 KB, `index` 89.1 KB, `app` 52.0 KB, `api-client` 29.3 KB). The CI step `bundle:check` reports it and does not fail the build until this is fixed.
- **PERF-02** Orbit the Overview for 30 minutes on the target laptop (browser task manager or `renderer.info`) and confirm the geometry and texture counts stop growing after the warm-up.
- **PERF-03** Measure frame rate (60 fps at Overview), first meaningful frame and draw calls on the target laptop by hand, then record the numbers here.


## Runbook

**Start and stop**

| Environment | Start | Stop |
| --- | --- | --- |
| local (apps on your machine, Dev Services database) | `cd backend && ./mvnw -pl castor-core quarkus:dev` and `cd frontend && npm run dev` | Ctrl+C in each terminal (the throwaway database goes with the backend) |
| local with the Docker database | `docker compose --profile local up -d --wait`, then the two commands above with `-Dquarkus.profile=local` | `docker compose --profile local down` (keeps the data; add `-v` to wipe it) |
| dev (everything in containers) | `docker compose --profile dev up -d --build --wait` | `docker compose --profile dev down` (add `-v` to remove the database volume) |

**Reset dev data.** Dev reseeds on every backend start (`CASTOR_RESET_ON_START=true`): `docker compose --profile dev restart castor-dev-backend`. For a clean database as well: `docker compose --profile dev down -v && docker compose --profile dev up -d --build --wait`. Local with the Docker database: `docker compose --profile local down -v && docker compose --profile local up -d --wait`. Only dev and test may wipe data; the backend refuses `CASTOR_RESET_ON_START` anywhere else.

**Logs and metrics**

- Logs: `docker compose --profile dev logs -f castor-dev-backend` (also `castor-dev-frontend`, `castor-dev-db`). Dev logs are JSON with a `requestId` that is also in every error response; local logs are plain text.
- Health and metrics are on the management port, not published in dev. Local: http://localhost:9000/q/health/ready and http://localhost:9000/q/metrics. In dev they are not reachable from the host (only the frontend port is published): use a local run, or the logs. Custom metrics: `castor_validation_duration`, `castor_publish_count`, `castor_import_count`, `castor_spatial_query_duration`.
- Version of what is running: `GET /api/v1/version` (also in the login card and the Help popover).
- Every change to a layout is in the audit log: `GET /api/v1/ports/westports/audit` (admin).

**Common failures**

| Symptom | Cause and fix |
| --- | --- |
| Satellite tiles do not load (grey ground in 2D and 3D, the rest works) | The browser cannot reach `tiles.maps.eox.at` (offline, proxy or firewall), or `VITE_SATELLITE_TILE_URL` is wrong. Open the tile URL from [Data](#phase-1-scope) in a tab. Designing still works on "None (grid)" (Basemap in the bottom strip). |
| Backend: "database not ready", health `DOWN`, `connection refused` | The database container is still starting or unhealthy. `docker compose --profile dev ps` and wait for `healthy`; `docker compose --profile dev logs castor-dev-db`. Local with the Docker database: check `LOCAL_DB_PORT` in `.env`. The first start after a reset runs all migrations and the seed: allow a minute. |
| "Port already in use" / `Address already in use` | Another process has 8081 (backend), 5173 (frontend), 9000 (management), 5432 (local database) or `DEV_WEB_PORT` (8080). Stop it, or change the port: `DEV_WEB_PORT` and `LOCAL_DB_PORT` in `.env`; `quarkus.http.port` for the backend. A leftover `quarkus:dev` is the usual cause. |
| Tests or `quarkus:dev`: "Could not find a valid Docker environment" / Testcontainers cannot reach Docker | Docker is not running, or the user cannot reach its socket. Start Docker Desktop (or the daemon) and run `docker info`. On Linux add your user to the `docker` group; with a non-default socket set `DOCKER_HOST`. Behind a proxy the first run needs to pull `postgis/postgis:18-3.6`. |
| Backend refuses to start: "Refusing to wipe data", `CHANGE_ME`, admin password, CORS | A guard in [Environment configuration](#environment-configuration): fix the value in `.env`. |
| Sign-in works but the app asks again after a reload | By design: credentials live in memory only. |
| Playwright: "needs the running dev stack" skipped, or login fails | Set `E2E_BASE_URL=http://localhost:8080` and start the dev stack with demo login on (`CASTOR_DEMO_LOGIN=true`, which dev sets). |

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

#### JOIN-03: Laptop layout and accessibility

1. Target width is 1024 px and up. At 1100 px and below the right panel is 300 px, the palette 220 px and the tool strip shows icons only (the names stay for screen readers, the tooltips are unchanged); "Save draft" and "Publish" keep their text. Below 1024 px a notice sits under the tool strip, the right panel is a drawer over the stage (`min(340px, 92vw)`, with a close button), the SYNTHETIC badge is hidden and Design adds that editing needs 1024 px. Design is warned, not blocked.
2. The Design bottom strip cuts its left-hand tools before History and the issues button when the window is too narrow. At 1100 px and below it drops the "CRS WGS84 / metres" text (the Inspector's layout summary names both CRS) and the word "Basemap", and the basemap select is narrower, so every tool fits at 1024 px.
3. Reduced motion (OS setting): Rotate and R are refused with the toast "Auto-rotate is off because reduced motion is on"; presets jump instead of flying; the hover glow is steady; the water stands still.
4. WCAG 2.2 AA: axe-core runs in Playwright on login, Monitor and Design (`e2e/a11y.spec.ts`); a serious or critical issue fails the build.
5. The e2e suite has no screenshot or pixel comparison, at the product owner's request. The layout checks of the responsive spec stay.

#### DESIGNER-08: Undo/redo and edit history

1. Every user action is one undo step: place, finished drawing, one drag, one field change, one vertex removal, duplicate, delete and import (DESIGNER-07, DESIGNER-06, DESIGNER-04). Undo applies the inverse API call (DELETE a created asset, PATCH back the previous values, re-create a deleted asset with the same id via POST with an id); redo re-applies it. Steps are kept in memory, last 100; a new action clears the redo stack.
2. A step that hits a 409 on undo or redo (another editor changed or deleted the asset since) is dropped, not kept for a later retry: a toast says "Cannot undo/redo: `<name>` was changed by `<user>`" instead of the generic conflict message.
3. Ctrl/Cmd+Z undoes, Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z redoes; the toolbar Undo and Redo buttons are disabled with nothing to undo or redo. Ctrl+S still means "Check and save" (DESIGNER-07); the browser's own save is prevented.
4. The "History (N)" popover of the bottom strip lists the last 60 entries, newest first, as "HH:MM `<label>`": edits, "Undo: `<label>`", "Redo: `<label>`" and basemap changes (DESIGNER-02); it does not undo anything itself.

#### DESIGNER-07: Inspector

1. Every field saves itself: one PATCH per change and one undo step "<Label> of <name>" (the provenance is one step, "Provenance of <name>"). Detail groups and the provenance are sent whole, so the undo puts back the group as it was. A value the rules refuse is not stored: the field keeps the stored value and the toast gives the reason.
2. Units: metres, degrees true (heading, one decimal), percent for design fill and usable share (stored as fractions), t/m2, kPa, coordinates to 7 decimals. Areas are m2 with hectares.
3. A storage record is created by the first cargo choice, with the type's default capacity (0 t when the type has none), usable share 100 %, design fill 0 %, exposed to the weather.
4. Warehouse door side is the compass side (NORTH, EAST, SOUTH, WEST), not rotated with the quay frame. Existing warehouses get NORTH.
5. Minimums: the catalogue's, plus bay and row pitch 5 m, pad max height 1 m, berth length 20 m and berth depth 1 m. The server applies the same rules to every save.
6. Delete asks first and lists the links that go with the asset. Duplicate and delete are each one undo step (DESIGNER-08); a deleted asset comes back with the same id, but its own links do not.
7. An exclusion override needs a reason of 10 to 500 characters, is audited and is not undoable.
8. Check and save waits for the saves in flight, re-reads the draft and re-validates it, then reports the largest rounding change of the session (mm, one decimal).
9. The Inspector reads shapes in the quay frame, so it shows and checks nothing before the port has loaded it.

#### SETUP-03: Architecture decisions

##### 1. Coordinate system, port origin and quay frame

A measured 470 m berth gave: ellipsoid 470.405 m, EPSG:3380 470.405 m, EPSG:3375 (Peninsula RSO) 470.467 m. EPSG:3380 is therefore the measurement CRS.

1. **Storage CRS:** WGS84 EPSG:4326, GeoJSON axis order `[longitude, latitude]`, 7 decimals (about 1 cm).
2. **Measurement CRS:** EPSG:3380 (GDM2000 / Selangor Grid). The backend uses PostGIS `ST_Transform` for lengths, areas and distances.
3. **Port origin:** lon 101.3066304, lat 2.9534057 (near the conventional terminal main gate).
4. **Quay frame:** local tangent plane at the origin, turned so local +X runs along the quay. Frame rotation `rotDeg` = -120.299 deg (angle of local +X, counter-clockwise from east). Local +X points to heading 210.3 deg true (along the quay towards the container terminals); local +Y points to heading 120.3 deg true (inland). Quay edge is local y = 0; water is y < 0.
5. **Conversion formulas** (backend seed/import code and the frontend geo module CORE-01 use exactly these):

```
rad = pi / 180
mLat = 111132.92 - 559.82*cos(2*lat0*rad) + 1.175*cos(4*lat0*rad)   (m per degree latitude)
mLon = 111412.84*cos(lat0*rad) - 93.5*cos(3*lat0*rad)               (m per degree longitude)
a = rotDeg*rad; c = cos(a); s = sin(a)
local -> WGS84: lon = lon0 + (x*c - y*s) / mLon ; lat = lat0 + (x*s + y*c) / mLat
WGS84 -> local: e = (lon - lon0)*mLon ; n = (lat - lat0)*mLat ; x = e*c + n*s ; y = -e*s + n*c
heading (deg true, clockwise from north) of local rotation r (deg, counter-clockwise from local +X):
  heading = (90 - r - rotDeg) mod 360 ; r = 90 - heading - rotDeg, normalised to -180..180
```

6. **3D scene axes:** scene X = local x, scene Y = up (ground y = 0), scene Z = -local y.
7. **Port extent:** lon 101.26 to 101.36, lat 2.90 to 3.02 (polygon in `ref.port.extent`).
8. **Control points** (APPROXIMATE until Westports supplies a survey; used for round-trip tests; formulas reproduce them within 0.1 m):

| Point | lon | lat | local x | local y |
|---|---|---|---|---|
| Main gate | 101.3093602 | 2.9518844 | -7.9 | 346.9 |
| Coastline point 0 | 101.3094176 | 2.9579603 | -591.2 | 13.4 |
| Coastline point 40 | 101.2950289 | 2.9335530 | 2546.1 | -6.1 |
| Coastline point 80 | 101.2796057 | 2.9065244 | 5991.7 | 21.3 |

9. **Height datum:** not set in Phase 1. Elevation is 0 (flat ground); heights are metres above ground. _Open item for Westports sign-off._

Values are seeded in `ref.port` (CORE-02) and used by CORE-01 (frontend geo module) and the backend seed/import code. Survey work is out of scope.

##### 2. Data sources, basemap licences and tracing rules

Seeded as `ref.data_source` (CORE-02):

| code | name | licence | may_persist | may_trace |
|---|---|---|---|---|
| OSM | OpenStreetMap | ODbL-1.0 (attribution) | true | n/a (vector import) |
| SYNTHETIC | Castor synthetic layout | internal | true | n/a |
| OPERATOR | Operator-entered in the designer | internal | true | n/a |
| SENTINEL2_EOX | Sentinel-2 cloudless by EOX | CC BY-NC-SA 4.0 | false | true (result APPROXIMATE) |
| LICENSED_BASEMAP | Licensed basemap (to be chosen) | commercial licence | false | true |
| SURVEY | Survey / GIS import | customer agreement | true | n/a |
| WESTPORTS_GIS | Westports GIS/CAD (future) | customer agreement | true | n/a |
| GOOGLE | Google Maps | Google Maps Platform terms | false | false |

1. Basemaps offered in the designer: "Sentinel-2 (EOX)" (default), "None (grid)", "Google Maps (lookup only)". On Google, drawing and placing catalogue assets are blocked with: "Google Maps (lookup / reference only): tracing into stored geometry is not allowed. Switch the basemap first."
2. Shapes traced on Sentinel-2 get source `SENTINEL2_EOX` (traced), accuracy `APPROXIMATE`, note "Traced from 10 m satellite imagery: approximate, not a survey".
3. Satellite tile URL (EOX WMTS, Web Mercator), max useful zoom 14: `https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg`
4. Attribution text (login card, Help popover and app footer): "Imagery: Sentinel-2 cloudless by EOX IT Services GmbH (CC BY-NC-SA 4.0), modified Copernicus Sentinel data 2024. Outlines: (c) OpenStreetMap contributors (ODbL)."
5. Sentinel-2 imagery is non-commercial: the Phase 1 demo is treated as non-commercial. A licensed basemap must be chosen before commercial use. _Open item for sign-off._
6. `GOOGLE` has `may_persist = false` and `may_trace = false`. Import jobs and the asset API refuse geometry whose source has `may_persist = false` (tested in DESIGNER-04).

##### 3. Accuracy labels and provenance rules

| class (`ref.accuracy_class`) | label | colour | meaning |
|---|---|---|---|
| SURVEYED | Surveyed | #2F8F6B | backed by a survey |
| VERIFIED | Imported verified | #3C6FB4 | imported GIS data checked against control points |
| OPERATOR | Operator-entered | #E8A317 | typed or placed by a person |
| APPROXIMATE | Approximate | #8A8F98 | traced from imagery or synthetic |

1. Every asset revision stores: source code, accuracy class, captured date, free-text note.
2. Defaults: placed in the designer -> source OPERATOR, accuracy APPROXIMATE, note "Placed in the layout designer"; drawn on Sentinel-2 -> see section 2; GeoJSON import without provenance -> SURVEY / VERIFIED; seed data -> SYNTHETIC / APPROXIMATE; OSM -> OSM / APPROXIMATE.
3. Wording mapping used by other documents: surveyed -> SURVEYED; imported -> VERIFIED (after the control-point check) or OPERATOR; traced -> APPROXIMATE; approximate -> APPROXIMATE.
4. UI: an approximate shape shows a "NO SURVEY" badge in the inspector (tooltip "Only a rough imagery-based or synthetic position exists") and a dashed outline when accuracy colouring is on.
5. Help text with the accuracy field: "False accuracy is worse than an honest approximate label: only mark a shape Surveyed when a survey backs it."
6. A shape whose source is "Traced from unlicensed imagery" blocks publishing (error), see DESIGNER-03.

##### 4. Layout versioning model and stable asset IDs

1. A port has layout versions with status `DRAFT`, `PUBLISHED`, `SUPERSEDED`, `DISCARDED`. At most one DRAFT and one PUBLISHED version per port (partial unique indexes).
2. Asset identity (`layout.asset`: id UUID, port, code, type) is permanent and never deleted. Everything that can change lives in `layout.asset_revision`, one row per (asset, layout version).
3. Creating a draft copies every revision row of the version it is based on (published version, an older version for restore, or nothing for a blank layout). Deleting an asset in a draft removes its revision row only; the identity stays.
4. Edits are saved immediately, one asset at a time (POST/PATCH/DELETE), with optimistic concurrency (`row_version`, ETag / If-Match). Only DRAFT rows can change; a trigger rejects changes to other versions.
5. Publish validates the draft; with zero errors the draft becomes PUBLISHED and the previous PUBLISHED becomes SUPERSEDED. Published versions are immutable.
6. Restore = create a new draft from an older version (same asset IDs), then publish as usual.
7. Monitor always shows the PUBLISHED version; Design always edits the DRAFT.
8. Every create, update, delete, publish, restore and discard writes an audit row (who, when, before, after).

Implemented by CORE-04 and DESIGNER-01..04.

##### 5. REST API conventions

1. Base path `/api/v1`; JSON only (`Content-Type: application/json`, else 415); GeoJSON responses use `application/geo+json`.
2. Port-scoped paths `/api/v1/ports/{portCode}/...`; layout-scoped paths `/api/v1/layouts/{layoutId}/...`.
3. PRD contracts kept exactly:
   - `GET /api/v1/ports/{portCode}/layouts/current`: published layout and asset geometry
   - `POST /api/v1/layouts/{layoutId}/assets`: create asset in a draft with optimistic revision
   - `POST /api/v1/layouts/{layoutId}/publish`: validate and publish an immutable version
4. Errors are RFC 9457 Problem Details with `type` `https://replica8.it/problems/<slug>`, `title`, `status`, `detail`, `instance`, optional `errors: [{code, pointer, assetId, message}]`. 400 validation, 401 unauthenticated, 403 wrong role, 404 not found, 409 conflict (stale If-Match, draft already exists), 412 missing If-Match on a write, 415 media type, 422 business rule failed. 500 returns only an error id, never stack traces or SQL. SETUP-04 implements the format.
5. Concurrency: GET returns `ETag` = row_version; PATCH/DELETE require `If-Match`; stale -> 409.
6. Coordinates are WGS84 `[lon, lat]`, 7 decimals, written only by the shared GeoJSON mapper (CORE-04).
7. Computed values (`areaM2`, `perimeterM`, `lengthM`) are returned, never stored.
8. Roles: VIEWER reads; EDITOR edits drafts; PUBLISHER publishes, restores and discards; ADMIN everything.
9. OpenAPI 3.1 is generated from the code at `/q/openapi` (ADMIN only outside dev) and describes every Phase 1 endpoint. A snapshot is committed at `backend/castor-core/src/main/resources/openapi/castor-api-v1.yaml`; CI fails when code and snapshot differ (contract test).
10. Versioning: additive changes only inside v1; breaking changes need `/api/v2`.
11. **Phase 2 live-record envelope (reserved now, not used in Phase 1):** `eventId`, `sourceSystem`, `sourceEventId`, `occurredAt`, `receivedAt`, `quality` (`OBSERVED` | `INFERRED` | `SIMULATED`), `version`, `stale`. Phase 1 layout data uses the provenance fields of section 3 instead (`source`, `accuracy`, `capturedOn`, `note`).

##### 6. Geometry tolerances and validation rule catalogue

1. Tolerances: save/reload and GeoJSON round trip <= 1 cm (7-decimal rounding); coordinate conversion backend vs frontend <= 1 cm; projection round trip <= 1 mm; control-point check <= 0.1 m against the approximate control points. Tolerance against a real survey: _TBD with Westports_.
2. Rule catalogue. Errors block publishing; warnings do not.

| code | level | message |
|---|---|---|
| GEOM_INVALID | error | "&lt;name&gt; has an invalid shape" |
| SELF_INTERSECTION | error | "&lt;name&gt; has a self-intersecting outline and cannot be published" |
| TOO_FEW_POINTS | error | "&lt;name&gt; needs at least three corners" / "needs at least two points" |
| TOO_SMALL | error | "The outline is smaller than 4 m2" (drawn polygon types only; placed equipment such as a 1.2 m bollard is not checked) |
| WRONG_GEOMETRY_KIND | error | "&lt;name&gt;: geometry does not match type &lt;type&gt;" |
| OUT_OF_PORT | error | "&lt;name&gt; lies outside the port extent" |
| ON_WATER | error | "Extends past the quay edge into the water" |
| OUTSIDE_LAND | error | "Outside the land area" |
| IN_EXCLUSION_ZONE | error | "Inside safety exclusion zone &lt;zone&gt;. Move it, or record a documented override in the inspector" |
| OVERLAP | error | "Overlaps &lt;other name&gt;" |
| BERTH_OVERLAP | error | "Overlaps &lt;other berth&gt; along the quay" |
| CRANE_OFF_BERTH | error | "A rail-mounted quay crane must stand on a berth" |
| DIM_OUT_OF_RANGE | error | "&lt;Label&gt;: enter a number of at least &lt;min&gt;" |
| UNKNOWN_TYPE | error | "&lt;name&gt;: unknown asset type &lt;type&gt;" |
| TRACE_SOURCE_NOT_ALLOWED | error | "&lt;name&gt; was traced from unlicensed imagery. Publishing is blocked until its source is replaced with licensed or surveyed data" |
| ON_ROAD | warning | "&lt;block&gt; sits on road &lt;road&gt;" |
| PILE_SLOT_TOO_SMALL | warning | "&lt;block&gt;: a &lt;bp&gt; x &lt;rp&gt; m slot is too small to hold a pile" |
| APPROXIMATE_SOURCE | warning | "&lt;n&gt; shapes have no survey" |

3. Placement and drag checks use the same rules as publish (no rule is an error in one place and a warning in another).
4. Placement check order (first failure wins): ON_WATER, OUTSIDE_LAND, IN_EXCLUSION_ZONE, OVERLAP. Berths: BERTH_OVERLAP. Rail quay cranes: CRANE_OFF_BERTH.
5. Overlap applies to solid types (storage pads, buildings, warehouses, solid assets, mobile equipment); lines, roads, areas and terminal boundaries are not overlap-checked.
6. Exclusion zones list allowed types (default for an exclusion zone: liquid tank, pipeline, pump station, exclusion zone). An override needs a written reason, is shown in the inspector and is audited.

DESIGNER-03 implements the catalogue; the frontend uses the same codes.

### Design review

There are no screenshot comparisons: the e2e specs check layout, behaviour and accessibility, and the look is reviewed by people in the PR. `frontend/e2e/responsive.spec.ts` checks what fits at 1024, 1280, 1440, 1920 and 900 px.

### Credits and origins

- **Basemap and data.** Imagery: Sentinel-2 cloudless by [EOX IT Services GmbH](https://s2maps.eu) (CC BY-NC-SA 4.0), contains modified Copernicus Sentinel data 2024; non-commercial, see [Data sources](#2-data-sources-basemap-licences-and-tracing-rules). Outlines (buildings, roads, rail, coastline, water): (c) [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, ODbL 1.0, kept in the committed extract with its provenance. The attribution text is shown on the login card, in the Help popover and in the app footer.
- **3D foundation.** The scene is based on an existing 3D port project; the earlier Portwise prototype in [`portwise/`](portwise/README.md) (MIT) is part of that lineage. Repository URL of the original project and of its fork, its licence, and what Castor reuses from it (the stage, the models, the camera): _open item, to be provided by the product owner and filled in here before Phase 1 sign-off._
- **Libraries.** [three.js](https://threejs.org) (MIT) and [@react-three/fiber](https://github.com/pmndrs/react-three-fiber) (MIT); React, React Router, TanStack Query, Zustand, Zod, Radix UI, Tailwind CSS, shadcn/ui patterns, lucide-react, Sonner, Vite, Vitest, Playwright and axe-core (MIT, Apache-2.0 or MPL-2.0); Quarkus, Hibernate, Flyway, PostgreSQL JDBC (Apache-2.0, LGPL, PostgreSQL licence); PostGIS (GPL-2.0, run as a separate database container); Testcontainers, ArchUnit, JTS (MIT, Apache-2.0, EDL). Each library keeps its own licence; the exact versions are in `frontend/package-lock.json` and `backend/pom.xml`.
- Built by Djava Factory for the Westports Malaysia replica. The earlier Portwise prototype lives in `portwise/` and is a separate codebase.
