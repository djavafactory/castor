import { expect, test } from '@playwright/test'
import {
  Api,
  authFor,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  openTab,
  placeOnMap,
  signIn,
} from '../helpers/acceptance'

// PRD 19.1 item 9, on the dev stack: a publisher publishes a version with a note, the version and the audit log carry
// who and when, and an older version is restored into a new draft and published again with the same asset ids.
// It adds versions to the port, so it is for a stack that resets on start.
test.skip(!process.env.E2E_BASE_URL, needsStack)
test.setTimeout(120_000)

interface Version {
  id: string
  versionNo: number
  status: string
  publishedBy: string | null
  publishedAt: string | null
  restoredFromVersionNo: number | null
  note: string | null
}

const idsOf = async (api: Api, version: string) =>
  (await api.layout(version)).features.features.map((f) => f.id).sort()

test('publishing records the author and the time, and a restored version keeps its asset ids', async ({
  page,
  request,
}) => {
  const admin = new Api(request, 'admin')
  const seed = (await admin.versions()).find((v) => v.versionNo === 1)!
  const seedIds = await idsOf(admin, seed.id)
  expect(seedIds.length).toBeGreaterThan(100)

  await signIn(page, 'publisher')
  await openDesign(page, '2D')

  // Change the draft, so the version differs from v1: one more asset
  const api = new Api(request, 'publisher')
  const [x, y] = await freeSpot(await api.ensureDraft().then(() => api))
  const map = await goToLocal(page, api, x, y)
  const added = await placeOnMap(page, map, 'FORKLIFT', 'forklift', [x, y])

  // Publish with a note
  await openTab(page, 'Versions')
  const tab = page.getByRole('region', { name: 'Versions' })
  const note = `acceptance ${Date.now()}`
  await tab.getByPlaceholder('What changed').fill(note)
  const before = Date.now()
  await tab.getByRole('button', { name: 'Publish', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Publish' }).click()
  await expect(page.getByText(/^Version \d+ published\. Monitor shows it now\.$/)).toBeVisible()
  const after = Date.now()

  // The version row names the publisher and the time
  const versions = (await admin.versions()) as Version[]
  const live = versions.find((v) => v.status === 'PUBLISHED')!
  expect(live.publishedBy).toBe('publisher')
  expect(live.note).toBe(note)
  const at = Date.parse(live.publishedAt!)
  expect(at).toBeGreaterThan(before - 60_000)
  expect(at).toBeLessThan(after + 60_000)
  const row = tab.getByRole('table', { name: 'Published versions' }).getByRole('row').nth(1)
  await expect(row).toContainText(`v${live.versionNo}`)
  await expect(row).toContainText('live')
  await expect(row).toContainText('publisher')
  await expect(row).toContainText(note)
  expect(await idsOf(admin, live.id)).toContain(added.id)

  // The audit log has the same author and time
  const audit = await request.get('/api/v1/ports/westports/audit?action=LAYOUT_PUBLISHED&limit=5', {
    headers: { Authorization: authFor('admin') },
  })
  expect(audit.ok()).toBe(true)
  const entry = ((await audit.json()) as { items: { actor: string; at: string }[] }).items[0]!
  expect(entry.actor).toBe('publisher')
  expect(Math.abs(Date.parse(entry.at) - at)).toBeLessThan(60_000)

  // Restore v1: a draft with exactly the ids of v1, and the forklift is not in it
  await tab.getByRole('button', { name: 'Restore version 1', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Restore' }).click()
  await expect(
    page.getByText('Version 1 loaded. Asset IDs are unchanged. Publish to make it live.'),
  ).toBeVisible()
  const draft = (await admin.draft())!
  expect(draft.features.features.map((f) => f.id).sort()).toEqual(seedIds)

  // Publish it: the new live version is "restored from v1" with the ids of v1
  await tab.getByRole('button', { name: 'Publish', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Publish' }).click()
  // (the toast of the first publish may still be on screen: the API says when this one is live)
  await expect
    .poll(
      async () =>
        ((await admin.versions()) as Version[]).find((v) => v.status === 'PUBLISHED')
          ?.restoredFromVersionNo,
      { timeout: 20_000 },
    )
    .toBe(1)
  const restored = ((await admin.versions()) as Version[]).find((v) => v.status === 'PUBLISHED')!
  expect(restored.restoredFromVersionNo).toBe(1)
  expect(restored.versionNo).toBeGreaterThan(live.versionNo)
  expect(await idsOf(admin, restored.id)).toEqual(seedIds)

  const log = await request.get('/api/v1/ports/westports/audit?action=LAYOUT_RESTORED&limit=5', {
    headers: { Authorization: authFor('admin') },
  })
  expect(((await log.json()) as { items: unknown[] }).items.length).toBeGreaterThan(0)
})
