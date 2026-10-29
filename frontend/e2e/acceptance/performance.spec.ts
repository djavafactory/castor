import { mkdir, writeFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { LAYERS } from '../../src/components/scene/stage/layer-heights'
import {
  Api,
  authFor,
  needsStack,
  probe,
  signIn,
  signOut,
  waitForScene,
  type SceneStats,
} from '../helpers/acceptance'

// JOIN-04 part 3: performance and layer height budgets, measured on the dev stack and written to
// test-results/performance.json (the numbers of the README "Performance" section).
//
// The browser here renders with software WebGL (SwiftShader), so frame rates and first-frame times are far below an
// integrated GPU. Those two budgets are reported always and enforced only on the target laptop: PERF_TARGET_HARDWARE=1.
// Everything else (API times, payload sizes, draw calls, memory growth, layer heights) does not depend on the GPU
// and is enforced everywhere. The memory soak (warm-up plus orbit, minutes long) runs only with PERF_SOAK_MINUTES set:
// the "Performance soak" workflow, not every push.
test.skip(!process.env.E2E_BASE_URL, needsStack)
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const TARGET_HARDWARE = process.env.PERF_TARGET_HARDWARE === '1'
const SOAK_MINUTES = Number(process.env.PERF_SOAK_MINUTES ?? '0')
const WARMUP_SECONDS = Number(process.env.PERF_WARMUP_SECONDS ?? '90')
const REPORT = process.env.PERF_REPORT ?? 'test-results/performance.json'

const report: Record<string, unknown> = {
  measuredAt: new Date().toISOString(),
  renderer: 'software WebGL (SwiftShader)',
  targetHardware: TARGET_HARDWARE,
  soakMinutes: SOAK_MINUTES,
}

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

/** Frames drawn per second while the camera turns by itself (the frame loop only runs while something moves). */
async function framesPerSecond(page: Page, seconds: number) {
  return (await page.evaluate(`new Promise((resolve) => {
    let frames = 0
    const t0 = performance.now()
    const tick = () => {
      frames++
      const elapsed = performance.now() - t0
      if (elapsed >= ${seconds} * 1000) resolve((frames * 1000) / elapsed)
      else requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  })`)) as number
}

test('the full layout at Overview: first frame, frame rate, draw calls and containers', async ({
  page,
}) => {
  // a cold visit fills the cache, the measured one is warm
  await signIn(page, 'viewer')
  await waitForScene(page)
  await signOut(page, 'viewer')

  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('viewer')
  const t0 = Date.now()
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible({ timeout: 60_000 })
  await waitForScene(page)
  // the first meaningful frame: the layout and the buildings are drawn, not just the empty ground
  await expect
    .poll(
      async () => {
        const s = await probe(page, 'render')
        return s.calls > 40 && s.instances > 0
      },
      { timeout: 60_000, intervals: [100] },
    )
    .toBe(true)
  const firstFrameMs = Date.now() - t0

  await page.keyboard.press('1') // Overview
  await page.waitForTimeout(1500)
  const stats = await probe(page, 'render')
  // autoRotate keeps the frame loop running: the rate is what the scene costs per frame
  await page.getByRole('button', { name: 'Rotate' }).click()
  await page.waitForTimeout(1500)
  const fps = await framesPerSecond(page, 5)
  await page.getByRole('button', { name: 'Rotate' }).click()

  report.overview = {
    firstMeaningfulFrameMs: firstFrameMs,
    firstMeaningfulFrameBudgetMs: 4000,
    fps: round(fps),
    fpsBudget: 60,
    drawCalls: stats.calls,
    drawCallBudget: 400,
    triangles: stats.triangles,
    geometries: stats.geometries,
    textures: stats.textures,
    containerInstances: stats.instances,
  }
  expect(stats.calls).toBeLessThan(400)
  if (TARGET_HARDWARE) {
    expect(firstFrameMs).toBeLessThan(4000)
    expect(fps).toBeGreaterThanOrEqual(58)
    expect(stats.instances).toBeGreaterThan(25_000)
  }
})

test('a sweep of 12 angles: every flat layer vertex at its layer height', async ({ page }) => {
  await signIn(page, 'viewer')
  await waitForScene(page)
  await page.keyboard.press('3') // Yard: close to the ground layers, where they could fight
  await page.waitForTimeout(1500)

  const angles: Record<string, unknown>[] = []
  for (let i = 0; i < 12; i++) {
    const azimuth = i * 30
    // layer heights: every vertex of every flat layer is exactly at its height
    const flat = await probe(page, 'flat')
    expect(flat.length, 'flat layers in the scene').toBeGreaterThan(0)
    for (const layer of flat) {
      expect(layer.expectedY, layer.layer).toBe(LAYERS[layer.layer as keyof typeof LAYERS].y)
      expect(layer.worst, `${layer.layer} at ${azimuth} deg`).toBeLessThan(1e-4)
    }
    angles.push({ azimuth, layers: flat.map((l) => `${l.layer}:${l.vertices}`) })
    await probe(page, 'orbit', 30)
  }
  report.angleSweep = { angles: angles.length }
})

test('memory stays flat while the camera orbits', async ({ page }) => {
  test.skip(
    SOAK_MINUTES <= 0,
    'the memory soak runs in the "Performance soak" workflow: set PERF_SOAK_MINUTES',
  )
  test.setTimeout((WARMUP_SECONDS + SOAK_MINUTES * 60) * 1000 + 5 * 60_000)
  await signIn(page, 'viewer')
  await waitForScene(page)
  await page.keyboard.press('1')
  await page.getByRole('button', { name: 'Rotate' }).click()
  // warm-up: one full turn so the imagery tiles all around the port are loaded and cached, and the collector settles
  await page.waitForTimeout(WARMUP_SECONDS * 1000)

  const heap = () =>
    page.evaluate(
      () =>
        (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory
          ?.usedJSHeapSize ?? 0,
    )
  const sample = async () => {
    const s: SceneStats = await probe(page, 'stats')
    return { geometries: s.geometries, textures: s.textures, heapMb: (await heap()) / 1_048_576 }
  }

  const start = await sample()
  const samples = [start]
  const until = Date.now() + SOAK_MINUTES * 60_000
  while (Date.now() < until) {
    await page.waitForTimeout(Math.min(30_000, Math.max(1000, until - Date.now())))
    samples.push(await sample())
  }
  const end = samples.at(-1)!
  const peakHeap = Math.max(...samples.map((s) => s.heapMb))
  report.memory = {
    warmupSeconds: WARMUP_SECONDS,
    minutes: SOAK_MINUTES,
    samples: samples.length,
    geometries: [start.geometries, end.geometries],
    textures: [start.textures, end.textures],
    heapMb: [round(start.heapMb), round(end.heapMb)],
    peakHeapMb: round(peakHeap),
    budget:
      '30 min: GPU resource counts unchanged; shorter: under +50 %. JS heap growth under 25 %',
  }
  // GPU resources: after the 30 minute soak the counts must be the same as after the warm-up. A short run is still
  // filling the imagery tile cache as the camera turns, so it only rules out runaway growth (see README, PERF-02).
  const strict = SOAK_MINUTES >= 30
  const allowed = (n: number) => (strict ? n + 2 : Math.ceil(n * 1.5))
  expect(end.geometries).toBeLessThanOrEqual(allowed(start.geometries))
  expect(end.textures).toBeLessThanOrEqual(allowed(start.textures))
  // the JS heap goes up and down with the collector, but does not climb
  if (start.heapMb > 0) expect(end.heapMb).toBeLessThan(start.heapMb * 1.25)
})
