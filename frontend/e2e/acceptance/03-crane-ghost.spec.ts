import { expect, test } from '@playwright/test'
import {
  Api,
  freeSpot,
  goToLocal,
  metresBetween,
  needsStack,
  openDesign,
  pickTile,
  signIn,
  keepDraftClean,
  type StoredAsset,
} from '../helpers/acceptance'

// PRD 19.1 item 4, on the dev stack: a crane is placed from its ghost, turned to a true heading and resized in metres in
// the Inspector, saved, and after a reload it is where it was (no drift beyond 1 cm).
test.skip(!process.env.E2E_BASE_URL, needsStack)

const HEADING_DEG = 123.4
const LENGTH_M = 20.5
const WIDTH_M = 10.25

keepDraftClean('editor')

type LonLat = [number, number]
const ringOf = (a: StoredAsset) => (a.geometry.coordinates as LonLat[][])[0]!.slice(0, -1)

/** True bearing, degrees clockwise from north, of the way from a to b (good to a hundredth of a degree at 20 m). */
function bearing(a: LonLat, b: LonLat) {
  const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180)
  const east = (b[0] - a[0]) * Math.cos(lat)
  const north = b[1] - a[1]
  return ((Math.atan2(east, north) * 180) / Math.PI + 360) % 360
}

test('a crane placed from its ghost is turned to a true heading and resized in metres, and does not drift', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)

  // The ghost follows the pointer, R turns it, a click places it
  await pickTile(page, 'MOBILE_HARBOUR_CRANE', 'mobile harbour')
  const at = map.at(x + 60, y - 40)
  await page.mouse.move(at.x - 8, at.y - 4)
  await page.mouse.move(at.x, at.y)
  const pill = page.getByTestId('placement-pill')
  await expect(pill).toContainText('R rotates')
  await page.keyboard.press('r')
  await expect(pill).toContainText('Click to place')
  const created = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url()),
  )
  await page.mouse.click(at.x, at.y)
  const placed = (await (await created).json()).feature as StoredAsset
  expect(placed.properties.type).toBe('MOBILE_HARBOUR_CRANE')

  // Heading, length and width in the Inspector: each one saves at once
  await page.getByRole('tab', { name: 'Inspector' }).click()
  const save = async (label: string, value: number) => {
    const field = page.getByRole('spinbutton', { name: label, exact: true }).first()
    const patched = page.waitForResponse(
      (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${placed.id}`),
    )
    await field.fill(String(value))
    await field.press('Enter')
    const answer = await patched
    expect(answer.status(), await answer.text()).toBe(200)
    // the draft is read again before the next edit: the next save must carry the new row version
    await page.waitForLoadState('networkidle')
  }
  await save('Heading', HEADING_DEG)
  await save('Length', LENGTH_M)
  await save('Width', WIDTH_M)

  const before = (await api.asset(placed.properties.name)).asset
  expect(before.properties.headingDeg).toBeCloseTo(HEADING_DEG, 1)
  expect(before.properties.lengthM).toBeCloseTo(LENGTH_M, 2)
  expect(before.properties.widthM).toBeCloseTo(WIDTH_M, 2)

  // The footprint measures what was typed, along the true heading
  const ring = ringOf(before)
  const sides = [metresBetween(ring[0]!, ring[1]!), metresBetween(ring[1]!, ring[2]!)]
  expect(Math.max(...sides)).toBeCloseTo(LENGTH_M, 1)
  expect(Math.min(...sides)).toBeCloseTo(WIDTH_M, 1)
  const longSide = sides[0]! > sides[1]! ? [ring[0]!, ring[1]!] : [ring[1]!, ring[2]!]
  const along = bearing(longSide[0]!, longSide[1]!)
  // the long side runs along the heading, or against it
  const off = Math.min(
    Math.abs(along - HEADING_DEG),
    Math.abs(along - HEADING_DEG - 180),
    Math.abs(along - HEADING_DEG + 180),
  )
  expect(off).toBeLessThan(0.2)

  // Save and reload: the same crane, within 1 cm of where it was
  await page.reload()
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const after = (await api.asset(placed.properties.name)).asset
  expect(after.id).toBe(placed.id)
  expect(after.properties.headingDeg).toBe(before.properties.headingDeg)
  expect(after.properties.lengthM).toBe(before.properties.lengthM)
  expect(after.properties.widthM).toBe(before.properties.widthM)
  const drift = ringOf(after).map((p, i) => metresBetween(p, ring[i]!))
  expect(Math.max(...drift)).toBeLessThanOrEqual(0.01)
  expect(
    metresBetween(after.properties.anchor as LonLat, before.properties.anchor as LonLat),
  ).toBeLessThanOrEqual(0.01)
})
