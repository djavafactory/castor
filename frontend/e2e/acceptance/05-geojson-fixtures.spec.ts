import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import {
  Api,
  authFor,
  metresBetween,
  needsStack,
  openDesign,
  openTab,
  signIn,
  keepDraftClean,
} from '../helpers/acceptance'

// PRD 19.1 item 6, on the dev stack: a known GeoJSON file (e2e/fixtures/known-shapes.geojson, WGS84 corners computed
// from round local metres) is imported through the Versions tab and exported again; every corner comes back within 1 cm.
test.skip(!process.env.E2E_BASE_URL, needsStack)

const FIXTURE = new URL('../fixtures/known-shapes.geojson', import.meta.url)

const kept = keepDraftClean('editor')

type Position = [number, number]
interface Feature {
  id?: string
  geometry: { type: string; coordinates: unknown }
  properties: { name: string; anchor?: Position }
}

/** Every [lon, lat] of a geometry, a ring's closing point left out. */
function positions(g: Feature['geometry']): Position[] {
  switch (g.type) {
    case 'Point':
      return [g.coordinates as Position]
    case 'LineString':
      return g.coordinates as Position[]
    default:
      return (g.coordinates as Position[][])[0]!.slice(0, -1)
  }
}

test('known shapes are imported and exported again, every corner within 1 cm', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'editor')
  const text = await readFile(FIXTURE, 'utf8')
  const fixture = JSON.parse(text) as { features: Feature[] }
  expect(fixture.features).toHaveLength(4)

  await signIn(page, 'editor')
  await openDesign(page, '2D')
  await openTab(page, 'Versions')
  const tab = page.getByRole('region', { name: 'Versions' })

  // A dry run first: the report names what would happen and stores nothing
  await tab.getByLabel('GeoJSON file').setInputFiles({
    name: 'known-shapes.geojson',
    mimeType: 'application/geo+json',
    buffer: Buffer.from(text),
  })
  const card = tab.getByRole('region', { name: 'Import report' })
  await expect(card).toContainText('Import of known-shapes.geojson', { timeout: 30_000 })
  await expect(card).toContainText(/4 added, 0 updated, 0 skipped/)
  expect((await api.draft())!.features.features).toHaveLength(kept.ids?.size ?? 0)

  // Then for real
  await card.getByRole('button', { name: 'Import' }).click()
  await expect(page.getByText('4 features imported, 0 skipped')).toBeVisible({ timeout: 30_000 })

  // Export the draft through the API (the Export button downloads the same file) and compare corner by corner
  const draft = (await api.draft())!
  const response = await request.get(`/api/v1/layouts/${draft.layout.id}/export`, {
    headers: { Authorization: authFor('editor') },
  })
  expect(response.ok()).toBe(true)
  const exported = ((await response.json()) as { features: Feature[] }).features

  let worst = 0
  for (const wanted of fixture.features) {
    const back = exported.find((f) => f.properties.name === wanted.properties.name)
    expect(back, `${wanted.properties.name} is in the export`).toBeDefined()
    // a point comes back as a footprint round its anchor: the anchor is what was sent
    const got =
      wanted.geometry.type === 'Point' ? [back!.properties.anchor!] : positions(back!.geometry)
    const want = positions(wanted.geometry)
    expect(got, wanted.properties.name).toHaveLength(want.length)
    for (const corner of want) {
      const nearest = Math.min(...got.map((g) => metresBetween(corner, g)))
      worst = Math.max(worst, nearest)
    }
  }
  expect(worst).toBeLessThanOrEqual(0.01)
})
