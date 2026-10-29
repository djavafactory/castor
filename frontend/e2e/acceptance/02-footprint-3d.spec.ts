import { expect, test } from '@playwright/test'
import {
  Api,
  drawOnMap,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  probe,
  signIn,
  keepDraftClean,
  waitForScene,
} from '../helpers/acceptance'

// PRD 19.1 item 3, on the dev stack: a warehouse that is not a rectangle is drawn, its measured height is entered, and
// the 3D view shows exactly that footprint extruded to exactly that height.
test.skip(!process.env.E2E_BASE_URL, needsStack)

const MEASURED_HEIGHT_M = 14.5

keepDraftClean('editor')

test('an L-shaped warehouse is extruded in 3D from its own footprint to its measured height', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)

  // An L: six corners, not a rectangle
  const corners: [number, number][] = [
    [x + 20, y - 80],
    [x + 110, y - 80],
    [x + 110, y - 40],
    [x + 70, y - 40],
    [x + 70, y + 20],
    [x + 20, y + 20],
  ]
  const saved = await drawOnMap(page, map, 'WAREHOUSE_POLY', 'warehouse', corners)

  // The measured height is typed into the Inspector and saved at once
  await page.getByRole('tab', { name: 'Inspector' }).click()
  const height = page
    .getByRole('region', { name: 'Dimensions' })
    .getByLabel('Height', { exact: true })
  const patched = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${saved.id}`),
  )
  await height.fill(String(MEASURED_HEIGHT_M))
  await height.press('Enter')
  expect((await patched).status()).toBe(200)
  const stored = (await api.asset(saved.properties.name)).asset
  expect(stored.properties.heightM).toBe(MEASURED_HEIGHT_M)

  // Fly the 3D camera to it through the search (on the 2D map of the split view), then read the scene
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: 'Split' }).click()
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(saved.properties.name)
  await expect(page.getByRole('option').first()).toContainText(saved.properties.name)
  await search.press('Enter')
  await waitForScene(page)

  const frame = await api.frame()
  const ring = (stored.geometry.coordinates as number[][][])[0]!.slice(0, -1)
  // scene metres: x = local x, z = -local y
  const expected = ring.map(([lon, lat]) => {
    const [lx, ly] = frame.fromLonLat(lon!, lat!)
    return [lx, -ly] as [number, number]
  })
  // The warehouse stands alone on free land: everything drawn within 80 m of its anchor is the warehouse
  const [ax, ay] = frame.fromLonLat(stored.properties.anchor[0], stored.properties.anchor[1])
  await expect
    .poll(
      async () => {
        const near = await probe(page, 'near', ax, -ay, 80)
        return (
          Math.abs(near.maxY - MEASURED_HEIGHT_M) < 0.01 &&
          expected.every(([ex, ez]) =>
            near.top.some(([tx, tz]) => Math.hypot(tx - ex, tz - ez) < 0.02),
          )
        )
      },
      { timeout: 60_000, message: 'the extruded L-shaped footprint at the measured height' },
    )
    .toBe(true)

  // It stands on the ground, and the top has the six corners of the drawn outline, no more
  const near = await probe(page, 'near', ax, -ay, 80)
  expect(near.minY).toBeCloseTo(0, 2)
  expect(near.top).toHaveLength(ring.length)
})
