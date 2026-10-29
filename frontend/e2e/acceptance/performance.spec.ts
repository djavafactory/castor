import { mkdir, writeFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { Api, authFor, needsStack } from '../helpers/acceptance'

// JOIN-04 part 3: the API budgets (published layout, one asset write, reference features), measured on the dev stack
// and written to test-results/performance.json. Nothing here draws the 3D scene.
test.skip(!process.env.E2E_BASE_URL, needsStack)

const REPORT = process.env.PERF_REPORT ?? 'test-results/performance.json'

const report: Record<string, unknown> = { measuredAt: new Date().toISOString() }

test.afterAll(async () => {
  await mkdir(REPORT.replace(/[^/\\]+$/, '') || '.', { recursive: true })
  await writeFile(REPORT, JSON.stringify(report, null, 2))
  console.log(`performance report: ${REPORT}\n${JSON.stringify(report, null, 2)}`)
})

const p95 = (samples: number[]) =>
  [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * 0.95) - 1]!
const round = (n: number, d = 1) => Math.round(n * 10 ** d) / 10 ** d

async function timed(call: () => Promise<{ ok(): boolean }>) {
  const t = performance.now()
  const r = await call()
  const ms = performance.now() - t
  expect(r.ok()).toBe(true)
  return ms
}

test('the published layout, a single asset write and the reference features meet their budgets', async ({
  request,
}) => {
  const viewer = { Authorization: authFor('viewer') }

  // published layout: 300 ms p95, every request a full answer (no If-None-Match)
  await request.get('/api/v1/ports/westports/layouts/current', { headers: viewer }) // warm-up
  const layout: number[] = []
  for (let i = 0; i < 50; i++) {
    layout.push(
      await timed(() =>
        request.get('/api/v1/ports/westports/layouts/current', { headers: viewer }),
      ),
    )
  }
  report.publishedLayoutApi = { samples: layout.length, p95Ms: round(p95(layout)), budgetMs: 300 }
  expect(p95(layout)).toBeLessThan(300)

  // reference features: under 2 MB
  const reference = await request.get('/api/v1/ports/westports/reference-features', {
    headers: viewer,
  })
  expect(reference.ok()).toBe(true)
  const bytes = (await reference.body()).length
  report.referenceFeatures = { bytes, megabytes: round(bytes / 1_048_576, 2), budgetMegabytes: 2 }
  expect(bytes).toBeLessThan(2 * 1_048_576)

  // a single asset write: 150 ms p95, a rename of one asset of the draft, then put back
  const api = new Api(request, 'editor')
  const draft = await api.ensureDraft()
  const asset =
    draft.features.features.find((f) => f.properties.type === 'FORKLIFT') ??
    draft.features.features[0]!
  const original = asset.properties.name
  let rowVersion = asset.properties.rowVersion
  const writes: number[] = []
  for (let i = 0; i < 30; i++) {
    const name = i === 29 ? original : `${original} (${i})`
    const t = performance.now()
    const r = await request.patch(`/api/v1/layouts/${draft.layout.id}/assets/${asset.id}`, {
      headers: { Authorization: authFor('editor'), 'If-Match': `"${rowVersion}"` },
      data: { name },
    })
    writes.push(performance.now() - t)
    expect(r.ok(), await r.text()).toBe(true)
    rowVersion = (await r.json()).feature.properties.rowVersion
  }
  report.assetWrite = { samples: writes.length, p95Ms: round(p95(writes)), budgetMs: 150 }
  expect(p95(writes)).toBeLessThan(150)
})
