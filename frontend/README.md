# Castor frontend

React + Vite + Three.js single-page app: login, app shell, Design mode (layout designer), Monitor mode. Created in SETUP-05.

## Stack and versions

Node.js 24.22.0 LTS, Vite 8.3.5, React 19.3.1 (held: react-three-fiber 9.8 needs react < 19.4), TypeScript ~6.0.3 (held: typescript-eslint 8.72 needs < 6.1), Tailwind 4.3.3, Three 0.186.1, @react-three/fiber 9.8.2, TanStack Query 5.104.5, react-router 8.5.0, Zustand 5.0.15, zod 4.6.7, Vitest 5.0.5, Playwright 1.64.1. Full list: root [README](../README.md#technology-stack).

## Folder layout

```
src/app                 App, router, providers, query-client
src/features/<feature>  auth, shell, scene, monitor, designer, palette, inspector, versions, catalogue, details
src/components/ui       themed shadcn components
src/components/layout   layout components
src/lib                 api-client, env, geo, utils
src/types  src/test  e2e/
```

Features must not import from each other; share code through `src/lib` and `src/components` (ESLint rule). API base path is `/api/v1`; errors are RFC 9457 Problem Details.

## Run and test

Requires Node 24 (`.nvmrc`). Copy `.env.example` to `.env.local` first; an invalid or missing value shows a full-page configuration error.

The frontend needs no database or container: Vitest mocks the API with MSW and the Playwright smoke test serves the built app with fixed test values. To see real data, start the backend first (it brings its own PostGIS, see [backend/README.md](../backend/README.md#run-and-test)) with `cd ../backend && ./mvnw -pl castor-core quarkus:dev`.

There is no full end-to-end test (browser + backend + database) yet. Playwright currently runs `e2e/smoke.spec.ts` against the frontend alone; a suite that also starts the backend and database arrives with the compose setup (SETUP-08).

```
npm ci
npm run dev           # dev server on PORT (default 5173, strict)
npm run lint && npm run format:check && npm run typecheck
npm test              # Vitest (MSW for the API); npm run test:coverage
npm run test:e2e      # Playwright (builds and previews the app)
npm run api:generate  # regenerate src/types/api.ts from the OpenAPI snapshot
npm run api:check     # CI: fails when src/types/api.ts is out of date
```

## API client and stores

- Every request goes through `src/lib/api-client.ts`. `VITE_API_URL` is the API root (`/api` in containers) and callers pass paths below it, for example `/v1/ports/WESTPORTS/layouts/current`.
- API types come only from `src/types/api.ts` (generated from `backend/castor-core/src/main/resources/openapi/castor-api-v1.yaml`); never hand-write them.
- Stores live in `src/lib/stores`. The auth store is memory only. 3D code reads stores with `getState()` or transient subscriptions; panels use selectors with `useShallow`.

## Tests

- `npm test` runs Vitest (jsdom, jest-dom, an MSW server from `src/test/setup.ts`, `@react-three/test-renderer` for scenes).
- `npm run test:coverage` adds coverage gates: `src/lib` (including stores) at least 85%, overall at least 70% (`vitest.config.ts`).
- `npm run test:e2e` runs Playwright on Chromium with software WebGL. Without `E2E_BASE_URL` the built app is served locally and the API is mocked; with it, only the specs listed as `STACK_SPECS` in `playwright.config.ts` run, against that running stack (the dev compose stack resets on start). CI runs both. Helpers in `e2e/helpers`: `login(role)`, `enterDesign()`, `placeAsset(type, lon, lat)`, `drawPolygon(points)`, `expectNoA11yViolations(page)`.
- Screenshot baselines are per platform: `*-win32.png` for local Windows runs and `*-linux.png` for CI, which runs Playwright in the official `mcr.microsoft.com/playwright` image. After a visual change, refresh the Linux baselines in that image (from the repo root, which the build needs for `fixtures/`; Git Bash, Docker running):

  ```
  MSYS_NO_PATHCONV=1 docker run --rm --ipc=host -v "$(pwd -W)":/work -v /work/frontend/node_modules -w /work/frontend \
    mcr.microsoft.com/playwright:v$(cd frontend && node -p "require('@playwright/test/package.json').version")-noble \
    sh -c "npm ci && npx playwright test --update-snapshots"
  ```
