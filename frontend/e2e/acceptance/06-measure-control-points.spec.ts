import { expect, test } from '@playwright/test'
import {
  Api,
  authFor,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  pickTile,
  PlanMap,
  signIn,
  keepDraftClean,
} from '../helpers/acceptance'

// PRD 19.1 item 7, on the dev stack: distances and areas are measured in the metric CRS (EPSG:3380) and compared
// with the control points of the port, whose local positions are known (ref.control_point).
test.skip(!process.env.E2E_BASE_URL, needsStack)

keepDraftClean('editor')

interface ControlPoint {
  properties: { code: string; expected_local_x: number; expected_local_y: number }
}

async function controlPoints(request: import('@playwright/test').APIRequestContext) {
  const r = await request.get('/api/v1/ports/westports/control-points', {
    headers: { Authorization: authFor('viewer') },
  })
  expect(r.ok()).toBe(true)
  const byCode = new Map<string, [number, number]>()
  for (const f of ((await r.json()) as { features: ControlPoint[] }).features) {
    byCode.set(f.properties.code, [f.properties.expected_local_x, f.properties.expected_local_y])
  }
  return byCode
}

test('the distance between two control points, measured on the map, matches their known local positions', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  const points = await controlPoints(request)
  const gate = points.get('CP-MAIN-GATE')!
  const coast = points.get('CP-COAST-00')!
  const known = Math.hypot(gate[0] - coast[0], gate[1] - coast[1])

  await signIn(page, 'editor')
  await openDesign(page, '2D')
  // both points are on the screen at once when the map is centred between them
  const map = await goToLocal(page, api, (gate[0] + coast[0]) / 2, (gate[1] + coast[1]) / 2)

  await page.getByRole('button', { name: 'Measure' }).click()
  await map.click(gate[0], gate[1])
  await map.click(coast[0], coast[1])

  await page.getByRole('button', { name: /^History \(\d+\)$/ }).click()
  const line = page
    .getByText(/^Measured [\d.]+ m \(geodesic [\d.]+ m\), bearing [\d.]+ deg$/)
    .first()
  await expect(line).toBeVisible()
  const [, planar, geodesic] = /Measured ([\d.]+) m \(geodesic ([\d.]+) m\)/
    .exec((await line.textContent())!)!
    .map(Number) as [number, number, number]

  // the pointer lands within a pixel of each point: 2 m at 0.6 px/m
  expect(Math.abs(planar - known)).toBeLessThan(2.5)
  // the metric CRS and the WGS84 geodesic agree to a tenth of a percent
  expect(Math.abs(planar - geodesic) / planar).toBeLessThan(0.001)
})

test('the area of a drawn outline is measured in metres, and the control points are within 0.1 m', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map: PlanMap = await goToLocal(page, api, x, y)

  // 100 m by 60 m, and an irregular corner: the area is known from the corners
  const ring: [number, number][] = [
    [x + 20, y - 80],
    [x + 120, y - 80],
    [x + 120, y - 20],
    [x + 20, y - 20],
  ]
  const known = 100 * 60
  await pickTile(page, 'LAYDOWN_YARD', 'laydown')
  for (const [cx, cy] of ring) await map.click(cx, cy)
  const readout = page.getByTestId('draw-readout')
  const area = Number(/(\d+) m2/.exec((await readout.getByText(/m2$/).textContent()) ?? '')?.[1])
  expect(Math.abs(area - known) / known).toBeLessThan(0.02)
  const saved = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url()),
  )
  await page.keyboard.press('Enter')
  expect((await saved).status()).toBe(201)

  // the Inspector computes the area of the stored polygon: the same number to a hair
  await page.getByRole('tab', { name: 'Inspector' }).click()
  const stored = await page
    .getByRole('region', { name: 'Position' })
    .getByText(/^[\d.]+ m2 \(/)
    .textContent()
  expect(Math.abs(Number(/^([\d.]+)/.exec(stored!)![1]) - area)).toBeLessThan(1)

  // Verify accuracy: every control point within 0.1 m of its known position
  const draft = (await api.draft())!
  const report = await request.get(`/api/v1/layouts/${draft.layout.id}/accuracy-report`, {
    headers: { Authorization: authFor('editor') },
  })
  expect(report.ok()).toBe(true)
  const body = (await report.json()) as {
    controlPoints: { code: string; residualM: number | null; roundTripMm: number }[]
    maxResidualM: number
  }
  expect(body.controlPoints.length).toBeGreaterThanOrEqual(4)
  for (const p of body.controlPoints) {
    expect(p.residualM, p.code).not.toBeNull()
    expect(p.residualM!, p.code).toBeLessThanOrEqual(0.1)
    expect(p.roundTripMm, p.code).toBeLessThanOrEqual(1)
  }
})
