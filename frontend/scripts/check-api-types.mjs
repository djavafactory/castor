// Fails when src/types/api.ts is out of date with the committed OpenAPI snapshot.
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const spec = '../backend/castor-core/src/main/resources/openapi/castor-api-v1.yaml'
const committed = 'src/types/api.ts'
const dir = mkdtempSync(join(tmpdir(), 'castor-api-'))
const fresh = join(dir, 'api.ts')

try {
  const bin = join('node_modules', 'openapi-typescript', 'bin', 'cli.js')
  execFileSync(process.execPath, [bin, spec, '-o', fresh], { stdio: 'inherit' })
  const norm = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  if (norm(fresh) !== norm(committed)) {
    console.error(`${committed} is out of date. Run "npm run api:generate" and commit the result.`)
    process.exit(1)
  }
  console.log(`${committed} is up to date.`)
} finally {
  rmSync(dir, { recursive: true, force: true })
}
