import { expect, test, type Page } from '@playwright/test'
import {
  Api,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  placeOnMap,
  probe,
  signIn,
  keepDraftClean,
  waitForScene,
} from '../helpers/acceptance'

// PRD 19.1 item 5, on the dev stack: an asset is dragged in the 2D map and in the 3D view; each drag saves the
// canonical coordinates, and both views then show the asset at the same anchor.
test.skip(!process.env.E2E_BASE_URL, needsStack)

keepDraftClean('editor')

interface Gizmo {
  body(
    dx: number,
    dy: number,
  ): { from: { x: number; y: number }; to: { x: number; y: number } } | null
}

const isPatch = (r: { request(): { method(): string }; url(): string }) =>
  r.request().method() === 'PATCH' && /\/assets\/[^/]+$/.test(r.url())

async function anchorOf(api: Api, name: string) {
  const frame = await api.frame()
  const { asset } = await api.asset(name)
  const [lon, lat] = asset.properties.anchor as [number, number]
  const [x, y] = frame.fromLonLat(lon, lat)
  return { x, y }
}

const bodyPath = (page: Page, dx: number, dy: number) =>
  page.evaluate(
    ([a, b]) =>
      (globalThis as unknown as { __castorGizmo?: Gizmo }).__castorGizmo?.body(a!, b!) ?? null,
    [dx, dy] as const,
  )

test('a drag in 2D and a drag in 3D each save the new anchor, and both views show the same one', async ({
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

  // 3D: Split view, fly to the asset, press its body and drag 5 m along local x
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: 'Split' }).click()
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(name)
  await expect(page.getByRole('option').first()).toContainText(name)
  await search.press('Enter')
  await waitForScene(page)
  // the camera flight takes 600 ms: the grab point is the same five samples in a row
  const seen: string[] = []
  await expect
    .poll(
      async () => {
        seen.push(JSON.stringify(await bodyPath(page, 5, 0)))
        return seen.length >= 5 && seen.at(-1) !== 'null' && new Set(seen.slice(-5)).size === 1
      },
      { timeout: 20_000, intervals: [200] },
    )
    .toBe(true)
  const path = (await bodyPath(page, 5, 0))!
  const moved3d = page.waitForResponse(isPatch)
  await page.mouse.move(path.from.x, path.from.y)
  await page.mouse.down()
  await page.mouse.move(path.to.x, path.to.y, { steps: 10 })
  await page.mouse.up()
  expect((await moved3d).status()).toBe(200)
  const after3d = await anchorOf(api, name)
  expect(Math.hypot(after3d.x - (after2d.x + 5), after3d.y - after2d.y)).toBeLessThan(1)

  // The 3D view draws the warehouse at the stored anchor: the middle of what is drawn there is the anchor
  await expect
    .poll(
      async () => {
        const [x0, x1, z0, z1] = (await probe(page, 'near', after3d.x, -after3d.y, 70)).extent
        return Math.hypot((x0 + x1) / 2 - after3d.x, (z0 + z1) / 2 + after3d.y)
      },
      { timeout: 15_000 },
    )
    .toBeLessThan(0.5)

  // The 2D view puts the same asset at the same anchor: the press there picks it up
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: '2D' }).click()
  await page.keyboard.press('Escape')
  await map.settle()
  await map.click(after3d.x, after3d.y)
  await expect(page.getByRole('heading', { name })).toBeVisible()
})
