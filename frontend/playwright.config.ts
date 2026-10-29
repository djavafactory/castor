import { defineConfig, devices } from '@playwright/test'

const PORT = 4173
// E2E_BASE_URL points at a running stack (the dev compose stack, which resets on start). Without it the built app is
// served locally by vite preview and the API calls are expected to be mocked by the test.
const externalBaseURL = process.env.E2E_BASE_URL
// Specs that need the real backend and seed data: with E2E_BASE_URL only these run, without it they are left out.
// The other specs mock the API and run against the locally served build.
const STACK_SPECS = [
  '**/osm-buildings.spec.ts',
  '**/monitor.spec.ts',
  '**/map-2d.spec.ts',
  '**/issues.spec.ts',
  '**/versions.spec.ts',
  '**/place.spec.ts',
  '**/inspector.spec.ts',
  '**/geojson.spec.ts',
  '**/gizmo-3d.spec.ts',
  '**/split-view.spec.ts',
  '**/a11y.spec.ts',
  '**/visual.spec.ts',
  '**/responsive.spec.ts',
  '**/acceptance/*.spec.ts',
]

export default defineConfig({
  testDir: './e2e',
  ...(externalBaseURL ? { testMatch: STACK_SPECS } : { testIgnore: STACK_SPECS }),
  // The stack specs share one backend and its demo accounts, and the 3D scenes are heavy: one at a time
  workers: externalBaseURL ? 1 : undefined,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  // Screenshot baselines (JOIN-03): more than 0.5 % of the pixels different fails. CI never writes a baseline; they
  // are made by the "Visual baselines" workflow and committed in a reviewed PR.
  updateSnapshots: process.env.CI ? 'none' : 'missing',
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.005,
      animations: 'disabled',
      caret: 'hide',
      stylePath: './e2e/visual/screenshot.css',
    },
  },
  use: {
    baseURL: externalBaseURL ?? `http://localhost:${PORT}`,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Software WebGL so the 3D scene renders on machines and CI runners without a GPU
        launchOptions: {
          args: [
            '--use-gl=angle',
            '--use-angle=swiftshader',
            '--enable-unsafe-swiftshader',
            '--ignore-gpu-blocklist',
            // performance.memory in real bytes (the memory budget of JOIN-04)
            '--enable-precise-memory-info',
          ],
        },
      },
    },
  ],
  webServer: externalBaseURL
    ? undefined
    : {
        command: 'npm run build && npm run preview',
        url: `http://localhost:${PORT}`,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        env: {
          PORT: String(PORT),
          VITE_APP_ENV: 'local',
          VITE_API_URL: '/api',
          VITE_PORT_CODE: 'WESTPORTS',
          VITE_SATELLITE_TILE_URL: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
        },
      },
})
