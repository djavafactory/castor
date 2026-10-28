import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): export the draft as GeoJSON, try it as a dry run, then import one feature
// for real. The export names its features by asset id, so importing them updates the assets of the draft.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

test.setTimeout(180_000)

test('an editor exports the draft, sees the round-trip report and imports a feature', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('tab', { name: 'Versions' }).click()
  const tab = page.getByRole('region', { name: 'Versions' })
  await expect(tab.getByRole('button', { name: 'Export GeoJSON' })).toBeEnabled({ timeout: 20_000 })

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    tab.getByRole('button', { name: 'Export GeoJSON' }).click(),
  ])
  expect(download.suggestedFilename()).toMatch(/^castor-westports-v\d+-\d{4}-\d{2}-\d{2}\.geojson$/)
  const text = await readFile(await download.path(), 'utf8')
  const exported = JSON.parse(text) as {
    crs: { properties: { name: string } }
    castor: { schemaVersion: number; port: string }
    features: { id: string; properties: { provenance: { source: string } } }[]
  }
  expect(exported.crs.properties.name).toBe('urn:ogc:def:crs:OGC:1.3:CRS84')
  expect(exported.castor).toMatchObject({ schemaVersion: 1, port: 'westports' })
  expect(exported.features.length).toBeGreaterThan(100)
  expect(exported.features.every((f) => f.properties.provenance.source !== 'GOOGLE')).toBe(true)

  // the whole export as a dry run: nothing is stored, and the coordinates survive within 10 mm
  await tab.getByLabel('GeoJSON file').setInputFiles({
    name: download.suggestedFilename(),
    mimeType: 'application/geo+json',
    buffer: Buffer.from(text),
  })
  const card = tab.getByRole('region', { name: 'Import report' })
  await expect(card).toContainText(`Import of ${download.suggestedFilename()}`, { timeout: 60_000 })
  await expect(card).toContainText(/\d+ added, \d+ updated, 0 skipped/)
  const roundTrip = /largest difference ([\d.]+) mm/.exec((await card.textContent()) ?? '')
  expect(Number(roundTrip?.[1])).toBeLessThanOrEqual(10)
  await card.getByRole('button', { name: 'Cancel' }).click()
  await expect(card).toHaveCount(0)

  // one feature for real
  const one = { type: 'FeatureCollection', features: exported.features.slice(0, 1) }
  await tab.getByLabel('GeoJSON file').setInputFiles({
    name: 'one-feature.geojson',
    mimeType: 'application/geo+json',
    buffer: Buffer.from(JSON.stringify(one)),
  })
  await expect(card).toContainText('Import of one-feature.geojson', { timeout: 30_000 })
  await card.getByRole('button', { name: 'Import' }).click()
  await expect(page.getByText('1 features imported, 0 skipped')).toBeVisible({ timeout: 30_000 })
  await expect(card).toHaveCount(0)
})
