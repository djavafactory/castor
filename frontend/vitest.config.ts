import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default defineConfig((env) =>
  mergeConfig(
    viteConfig(env),
    defineConfig({
      test: {
        globals: true,
        environment: 'jsdom',
        env: {
          VITE_APP_ENV: 'test',
          VITE_API_URL: 'http://api.test/api',
          VITE_PORT_CODE: 'WESTPORTS',
          VITE_SATELLITE_TILE_URL: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
        },
        // jest-dom matchers and the MSW server (src/test/setup.ts)
        setupFiles: ['./src/test/setup.ts'],
        include: ['src/**/*.test.{ts,tsx}'],
        coverage: {
          provider: 'v8',
          reporter: ['text-summary', 'lcov'],
          include: ['src/**/*.{ts,tsx}'],
          exclude: ['src/types/api.ts', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/main.tsx'],
          thresholds: {
            // overall
            lines: 70,
            functions: 70,
            statements: 70,
            branches: 70,
            // pure logic and stores
            'src/lib/**': { lines: 85, functions: 85, statements: 85, branches: 85 },
          },
        },
      },
    }),
  ),
)
