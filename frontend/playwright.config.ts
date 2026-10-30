import { defineConfig, devices } from '@playwright/test'

const PORT = 4173
// E2E_BASE_URL points at a running stack (the dev compose stack, which resets on start). Without it the built app is
// served locally by vite preview and the API calls are expected to be mocked by the test.
const externalBaseURL = process.env.E2E_BASE_URL
// Specs that need the real backend and seed data: with E2E_BASE_URL only these run, without it they are left out.
// The other specs mock the API and run against the locally served build.
const STACK_SPECS = [
  '**/monitor.spec.ts',
  '**/map-2d.spec.ts',
  '**/issues.spec.ts',
  '**/versions.spec.ts',
  '**/place.spec.ts',
  '**/inspector.spec.ts',
  '**/geojson.spec.ts',
  '**/split-view.spec.ts',
  '**/a11y.spec.ts',
  '**/responsive.spec.ts',
  '**/gateway.spec.ts',
  '**/acceptance/*.spec.ts',
]

export default defineConfig({
  testDir: './e2e',
  ...(externalBaseURL ? { testMatch: STACK_SPECS } : { testIgnore: STACK_SPECS }),
  // The stack specs share one backend, its draft and its demo accounts: one at a time
  workers: externalBaseURL ? 1 : undefined,
  forbidOnly: !!process.env.CI,
  // One retry in CI: a real failure costs one more run, not two
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: externalBaseURL ?? `http://localhost:${PORT}`,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // No WebGL: the e2e tests never draw the 3D scene (the stage shows its "3D view not available" notice and the
        // palette its plain tiles), which keeps every test fast
        launchOptions: { args: ['--disable-3d-apis', '--disable-webgl'] },
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
