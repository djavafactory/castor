import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

function gitCommit(): string {
  if (process.env.GIT_COMMIT) return process.env.GIT_COMMIT
  try {
    return execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim()
  } catch {
    return 'unknown'
  }
}

function appVersion(): string {
  try {
    return readFileSync(path.resolve(import.meta.dirname, '../VERSION'), 'utf8').trim()
  } catch {
    return '0.0.0'
  }
}

export default defineConfig(({ mode }) => {
  // PORT has no VITE_ prefix, so it is read here and never reaches the browser bundle.
  const env = loadEnv(mode, process.cwd(), '')
  const port = Number(env.PORT) || 5173

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': path.resolve(import.meta.dirname, 'src') },
    },
    define: {
      __APP_VERSION__: JSON.stringify(appVersion()),
      __GIT_COMMIT__: JSON.stringify(gitCommit()),
      __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
    },
    server: { port, strictPort: true },
    preview: { port, strictPort: true },
    build: {
      rolldownOptions: {
        output: {
          // Routes are lazy-loaded in src/app/router.tsx; 3D libraries get their own chunk.
          manualChunks(id: string) {
            if (id.includes('/node_modules/three/') || id.includes('/node_modules/@react-three/')) {
              return 'three'
            }
            return undefined
          },
        },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      env: {
        VITE_APP_ENV: 'test',
        VITE_API_URL: 'http://api.test/api',
        VITE_PORT_CODE: 'WESTPORTS',
        VITE_SATELLITE_TILE_URL: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
      },
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/types/api.ts', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/main.tsx'],
      },
    },
  }
})
