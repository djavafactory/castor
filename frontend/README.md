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

Requires Node 24. The commands below apply once SETUP-05 adds the project.

```
npm ci
npm run dev        # dev server
npm run lint
npm test           # Vitest
npx playwright test
```