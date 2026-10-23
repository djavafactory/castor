import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

function gitCommit(): string {
  if (process.env.GIT_COMMIT) return process.env.GIT_COMMIT
  try {
    return execSync('git rev-parse --short=7 HEAD', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim()
  } catch {
    return 'unknown'
  }
}

// A release build gets BUILD_VERSION (X.Y.Z) from scripts/build-stamp.sh; anything else is <version>-dev+<commit>.
// package.json is always present (also inside the Docker build) and CI keeps it equal to the root VERSION file.
function appVersion(commit: string): string {
  if (process.env.BUILD_VERSION) return process.env.BUILD_VERSION
  const pkg = path.resolve(import.meta.dirname, 'package.json')
  const { version } = JSON.parse(readFileSync(pkg, 'utf8')) as { version: string }
  return `${version}-dev+${commit}`
}

function buildTime(): string {
  return process.env.BUILD_TIME || new Date().toISOString()
}

export default defineConfig(({ mode }) => {
  // PORT has no VITE_ prefix, so it is read here and never reaches the browser bundle.
  const env = loadEnv(mode, process.cwd(), '')
  const port = Number(env.PORT) || 5173
  const commit = gitCommit()

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': path.resolve(import.meta.dirname, 'src') },
    },
    define: {
      __APP_VERSION__: JSON.stringify(appVersion(commit)),
      __GIT_COMMIT__: JSON.stringify(commit),
      __BUILD_TIME__: JSON.stringify(buildTime()),
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
  }
})
