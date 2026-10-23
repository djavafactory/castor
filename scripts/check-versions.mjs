// Fails when the root VERSION file, backend/pom.xml (revision) and frontend/package.json differ (SETUP-11).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const root = join(import.meta.dirname, '..')
const read = (file) => readFileSync(join(root, file), 'utf8')

const SEMVER = /^\d+\.\d+\.\d+$/
const found = {
  'VERSION': read('VERSION').trim(),
  'backend/pom.xml <revision>': /<revision>([^<]*)<\/revision>/.exec(read('backend/pom.xml'))?.[1]?.trim(),
  'frontend/package.json': JSON.parse(read('frontend/package.json')).version,
  'frontend/package-lock.json': JSON.parse(read('frontend/package-lock.json')).version,
}

const expected = found.VERSION
const bad = Object.entries(found).filter(([, v]) => v !== expected)
if (!SEMVER.test(expected ?? '') || bad.length > 0) {
  console.error('Version files disagree (they must all equal VERSION, as MAJOR.MINOR.PATCH):')
  for (const [file, value] of Object.entries(found)) console.error(`  ${file}: ${value ?? 'missing'}`)
  process.exit(1)
}
console.log(`All version files are ${expected}.`)
