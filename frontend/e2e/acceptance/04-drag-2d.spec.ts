import { expect, test } from '@playwright/test'
import {
  Api,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  placeOnMap,
  signIn,
  keepDraftClean,
} from '../helpers/acceptance'

// PRD 19.1 item 5, on the dev stack: an asset is dragged in the 2D map; the drag saves the canonical coordinates and
// the map then shows the asset at the stored anchor.
test.skip(!process.env.E2E_BASE_URL, needsStack)

keepDraftClean('editor')

const isPatch = (r: { request(): { method(): string }; url(): string }) =>
  r.request().method() === 'PATCH' && /\/assets\/[^/]+$/.test(r.url())

async function anchorOf(api: Api, name: string) {
  const frame = await api.frame()
  const { asset } = await api.asset(name)
  const [lon, lat] = asset.properties.anchor as [number, number]
  const [x, y] = frame.fromLonLat(lon, lat)
  return { x, y }
}

test('a drag in 2D saves the new anchor, and the map shows the asset there', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)
  const placed = await placeOnMap(page, map, 'WAREHOUSE', 'warehouse', [x, y])
  const name = placed.properties.name
  const start = await anchorOf(api, name)

  // 2D: press the body and drag 40 m east, 25 m inland
  const moved2d = page.waitForResponse(isPatch)
  await map.drag([start.x, start.y], [start.x + 40, start.y + 25])
  expect((await moved2d).status()).toBe(200)
  await page.waitForLoadState('networkidle') // the next save must carry the new row version
  const after2d = await anchorOf(api, name)
  // one pixel at 0.6 px/m is 1.7 m: the pointer lands within a pixel or two of where it was put
  expect(Math.hypot(after2d.x - (start.x + 40), after2d.y - (start.y + 25))).toBeLessThan(4)

  // The map puts the asset at the stored anchor: a press there picks it up
  await page.keyboard.press('Escape')
  await map.settle()
  await map.click(after2d.x, after2d.y)
  await expect(page.getByRole('heading', { name })).toBeVisible()
})
