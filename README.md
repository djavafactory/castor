# Castor

Reusable port digital twin. The earlier Portwise prototype lives in [`portwise/`](portwise/README.md).

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

### Decisions

Record decisions and behaviour changes here, newest first.

- _No decisions recorded yet._

