// JOIN-04 performance budget: the frontend bundle without three (and react-three-fiber) stays under 250 KB gzipped.
// Run after `npm run build`: node scripts/check-bundle-size.mjs [dist-dir]. Prints every file; exits 1 over budget.
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'

const BUDGET_KB = 250
const dist = process.argv[2] ?? 'dist'
const assets = join(dist, 'assets')

const rows = readdirSync(assets)
  .filter((f) => /\.(js|css)$/.test(f))
  .map((f) => {
    const raw = readFileSync(join(assets, f))
    return { file: f, raw: raw.length, gzip: gzipSync(raw, { level: 9 }).length }
  })
  .sort((a, b) => b.gzip - a.gzip)

// The chunk the build names "three" holds three.js and @react-three: it is the 3D foundation and has its own line
const isThree = (f) => /^three-/.test(f)
const kb = (n) => (n / 1024).toFixed(1)
const own = rows.filter((r) => !isThree(r.file))
const three = rows.filter((r) => isThree(r.file))
const sum = (list) => list.reduce((s, r) => s + r.gzip, 0)

for (const r of rows)
  console.log(
    `${isThree(r.file) ? '3d ' : '   '}${kb(r.gzip).padStart(8)} KB gz  ${kb(r.raw).padStart(8)} KB raw  ${r.file}`,
  )
console.log(`\nwithout three: ${kb(sum(own))} KB gzipped (budget ${BUDGET_KB} KB)`)
console.log(`three chunk:   ${kb(sum(three))} KB gzipped (not counted)`)

if (process.env.BUNDLE_REPORT) {
  writeFileSync(
    process.env.BUNDLE_REPORT,
    JSON.stringify(
      { withoutThreeKb: +kb(sum(own)), threeKb: +kb(sum(three)), budgetKb: BUDGET_KB, files: rows },
      null,
      2,
    ),
  )
}
if (sum(own) > BUDGET_KB * 1024) {
  console.error(`over budget by ${kb(sum(own) - BUDGET_KB * 1024)} KB`)
  process.exit(1)
}
