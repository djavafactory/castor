import { expect, test } from '@playwright/test'
import {
  Api,
  drawOnMap,
  freeSpot,
  goToLocal,
  metresBetween,
  needsStack,
  openDesign,
  signIn,
  keepDraftClean,
} from '../helpers/acceptance'

// PRD 19.1 items 1 and 2, on the dev stack: search the port, the approved basemap, a polygon of six vertices that is
// stored, reloaded and read back as the same WGS84 ring, and the shapes the rules refuse.
test.skip(!process.env.E2E_BASE_URL, needsStack)
test.describe.configure({ mode: 'serial' })

const MAIN_GATE = '2.9518844, 101.3093602'

const kept = keepDraftClean('editor')

/** Six corners round (cx, cy): clear of the draw panel at the top left of the map. */
const hexagon = (cx: number, cy: number): [number, number][] => [
  [cx + 20, cy - 80],
  [cx + 90, cy - 80],
  [cx + 130, cy - 30],
  [cx + 90, cy + 20],
  [cx + 20, cy + 20],
  [cx - 10, cy - 30],
]

test('the port is found by its coordinates, the approved basemap is on, and a polygon of six vertices is placed', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')

  // Search the main gate by coordinates: the map goes there with a pin
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(MAIN_GATE)
  await expect(page.getByRole('option', { name: `Go to ${MAIN_GATE} (lat, lon)` })).toBeVisible()
  await search.press('Enter')
  await expect(page.getByTestId('map-pin')).toBeVisible()

  // The approved basemap (Sentinel-2 by EOX) is the one in use, with its attribution
  await expect(page.getByLabel('Basemap')).toHaveValue('sentinel2')
  await expect(page.getByLabel('Basemap')).toContainText('Sentinel-2 (EOX)')
  await page.getByRole('button', { name: 'More', exact: true }).click()
  await page.getByRole('button', { name: /Help and legend/ }).click()
  await expect(page.getByText(/Sentinel-2 cloudless by EOX IT Services GmbH/)).toBeVisible()
  await page.keyboard.press('Escape')

  // A polygon of six vertices on free land
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)
  const ring = hexagon(x, y)
  const saved = await drawOnMap(page, map, 'WAREHOUSE_POLY', 'warehouse', ring)
  const stored = (saved.geometry.coordinates as number[][][])[0]!
  expect(stored).toHaveLength(7)
  expect(saved.properties.provenance).toMatchObject({
    source: 'SENTINEL2_EOX',
    accuracy: 'APPROXIMATE',
  })

  // What was clicked is what was stored: each vertex is within a pixel of the pointer (1.7 m at 0.6 px/m)
  const frame = await api.frame()
  ring.forEach(([cx, cy], i) => {
    const [vx, vy] = frame.fromLonLat(stored[i]![0]!, stored[i]![1]!)
    expect(Math.hypot(vx - cx, vy - cy)).toBeLessThan(2 / map.scale)
  })
})

test('a polygon persists: after a reload the same WGS84 ring comes back, to the digit', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)
  const saved = await drawOnMap(page, map, 'WAREHOUSE_POLY', 'warehouse', hexagon(x, y))

  // A reload ends the session (credentials live in memory only): sign in again, open Design and read the draft
  await page.reload()
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const reloaded = (await api.draft())!.features.features
  const again = reloaded.find((f) => f.id === saved.id)!
  expect(again).toBeDefined()
  expect(again.geometry.coordinates).toEqual(saved.geometry.coordinates)
  expect(again.properties.anchor).toEqual(saved.properties.anchor)

  // Inspect it in the Inspector: the ring is listed vertex by vertex, in the stored longitude and latitude
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(saved.properties.name)
  await expect(page.getByRole('option').first()).toContainText(saved.properties.name)
  await search.press('Enter')
  await page.getByRole('tab', { name: 'Inspector' }).click()
  const inspector = page.getByRole('region', { name: 'Position' })
  await expect(inspector).toBeVisible()
  const ring = (saved.geometry.coordinates as number[][][])[0]!
  const lon1 = await inspector.getByLabel('Longitude of vertex 1').inputValue()
  const lat1 = await inspector.getByLabel('Latitude of vertex 1').inputValue()
  expect(Number(lon1.replace(',', '.'))).toBeCloseTo(ring[0]![0]!, 7)
  expect(Number(lat1.replace(',', '.'))).toBeCloseTo(ring[0]![1]!, 7)
  expect(metresBetween([ring[0]![0]!, ring[0]![1]!], [ring[0]![0]!, ring[0]![1]!])).toBe(0)
})

test('a self-crossing outline is not saved, and the server refuses crossing and invalid rings', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)

  // A bow tie on the map: the readout says so, Enter saves nothing
  let posted = false
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url())) posted = true
  })
  await page.getByTestId('asset-palette').getByRole('searchbox').fill('warehouse')
  await page.getByTestId('palette-tile-WAREHOUSE_POLY').click()
  for (const [dx, dy] of [
    [20, -80],
    [120, 20],
    [120, -80],
    [20, 20],
  ] as const) {
    await map.click(x + dx, y + dy)
  }
  await expect(page.getByTestId('draw-readout').getByRole('alert')).toHaveText(
    'The outline crosses itself.',
  )
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('draw-readout')).toBeVisible()
  await page.keyboard.press('Escape')
  expect(posted).toBe(false)

  // The same shapes sent straight to the API are refused with a reason, nothing is stored
  const frame = await api.frame()
  const draft = (await api.draft())!
  const at = (px: number, py: number) => frame.toLonLat(x + px, y + py)
  const send = (code: string, ring: [number, number][]) =>
    api.create(draft.layout.id, {
      type: 'WAREHOUSE_POLY',
      code,
      name: code,
      geometry: { type: 'Polygon', coordinates: [ring] },
      anchor: at(0, 0),
      heightM: 9,
    })
  const bowTie = [at(20, -80), at(120, 20), at(120, -80), at(20, 20), at(20, -80)]
  const crossed = await send('E2E-CROSS', bowTie)
  expect(crossed.status()).toBe(422)
  expect((await crossed.json()).errors[0].code).toBe('SELF_INTERSECTION')

  const twoCorners = await send('E2E-TWO', [at(20, -80), at(120, 20), at(20, -80)])
  expect(twoCorners.status()).toBe(422)
  expect(['TOO_FEW_POINTS', 'GEOM_INVALID']).toContain((await twoCorners.json()).errors[0].code)

  const open = await send('E2E-OPEN', [at(20, -80), at(120, -80), at(120, 20), at(20, 20)])
  expect(open.status()).toBe(422)
  expect((await api.draft())!.features.features).toHaveLength(kept.ids?.size ?? 0)
})
