import { defineConfig, devices } from '@playwright/test'

const PORT = 4173
// E2E_BASE_URL points at a running stack (the dev compose stack, which resets on start). Without it the built app is
// served locally by vite preview and the API calls are expected to be mocked by the test.
const externalBaseURL = process.env.E2E_BASE_URL

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
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
          VITE_APP_ENV: 'test',
          VITE_API_URL: '/api',
          VITE_PORT_CODE: 'WESTPORTS',
          VITE_SATELLITE_TILE_URL: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
        },
      },
})
