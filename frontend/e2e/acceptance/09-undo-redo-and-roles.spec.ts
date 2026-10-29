import { expect, test } from '@playwright/test'
import {
  Api,
  authFor,
  freeSpot,
  goToLocal,
  keepDraftClean,
  needsStack,
  openDesign,
  placeOnMap,
  signIn,
} from '../helpers/acceptance'

// PRD 19.1 extras, on the dev stack: undo and redo of place, move and delete, and the role checks (a viewer cannot edit,
// an editor cannot publish).
test.skip(!process.env.E2E_BASE_URL, needsStack)

keepDraftClean('editor')

const isWrite = (method: string) => (r: { request(): { method(): string }; url(): string }) =>
  r.request().method() === method && /\/layouts\/[^/]+\/assets(\/[^/]+)?$/.test(r.url())

test('place, move and delete are each undone and redone', async ({ page, request }) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)
  const undo = page.getByRole('button', { name: 'Undo' })
  const redo = page.getByRole('button', { name: 'Redo' })
  const draftHas = async (name: string) =>
    (await api.draft())!.features.features.some((f) => f.properties.name === name)

  // Place: undo removes it, redo brings it back
  const placed = await placeOnMap(page, map, 'WAREHOUSE', 'warehouse', [x, y])
  const name = placed.properties.name
  expect(await draftHas(name)).toBe(true)
  const gone = page.waitForResponse(isWrite('DELETE'))
  await undo.click()
  expect((await gone).status()).toBe(204)
  expect(await draftHas(name)).toBe(false)
  const back = page.waitForResponse(isWrite('POST'))
  await redo.click()
  expect((await back).status()).toBe(201)
  expect(await draftHas(name)).toBe(true)

  // Move: drag it 30 m along the quay; undo puts it back, redo moves it again
  const start = (await api.asset(name)).asset.properties.anchor as [number, number]
  const frame = await api.frame()
  const [sx, sy] = frame.fromLonLat(start[0], start[1])
  await map.settle()
  const moved = page.waitForResponse(isWrite('PATCH'))
  await map.drag([sx, sy], [sx + 30, sy])
  expect((await moved).status()).toBe(200)
  const at = async () => {
    const a = (await api.asset(name)).asset.properties.anchor as [number, number]
    return frame.fromLonLat(a[0], a[1])
  }
  const [mx] = await at()
  expect(mx - sx).toBeGreaterThan(25)
  const undone = page.waitForResponse(isWrite('PATCH'))
  await undo.click()
  expect((await undone).status()).toBe(200)
  const [ux, uy] = await at()
  expect(Math.hypot(ux - sx, uy - sy)).toBeLessThan(0.05)
  const redone = page.waitForResponse(isWrite('PATCH'))
  await redo.click()
  expect((await redone).status()).toBe(200)
  expect((await at())[0] - sx).toBeGreaterThan(25)

  // Delete: Inspector -> Delete -> confirm; undo brings the same asset back with the same id
  await page
    .getByRole('region', { name: 'Actions' })
    .getByRole('button', { name: 'Delete' })
    .click()
  const deleted = page.waitForResponse(isWrite('DELETE'))
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  expect((await deleted).status()).toBe(204)
  expect(await draftHas(name)).toBe(false)
  const restored = page.waitForResponse(isWrite('POST'))
  await undo.click()
  expect((await restored).status()).toBe(201)
  expect((await api.asset(name)).asset.id).toBe(placed.id)
})

test('a viewer cannot edit and an editor cannot publish', async ({ page, request }) => {
  const draft = (await new Api(request, 'editor').draft())!
  const asset = draft.features.features[0]!

  // The viewer is sent back to Monitor from Design and has no editing tabs; the API refuses the writes
  await signIn(page, 'viewer')
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByRole('button', { name: 'Monitor' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await expect(page.getByRole('tab', { name: /Inspector|Issues|Versions/ })).toHaveCount(0)
  const viewerWrite = await request.patch(`/api/v1/layouts/${draft.layout.id}/assets/${asset.id}`, {
    headers: { Authorization: authFor('viewer'), 'If-Match': `"${asset.properties.rowVersion}"` },
    data: { name: 'viewer was here' },
  })
  expect(viewerWrite.status()).toBe(403)
  expect(
    (
      await request.get('/api/v1/ports/westports/layouts/draft', {
        headers: { Authorization: authFor('viewer') },
      })
    ).status(),
  ).toBe(403)

  // The editor edits, but the Publish button is disabled and the API refuses
  await page.getByRole('button', { name: 'viewer', exact: true }).click()
  await page
    .getByRole('dialog', { name: 'User menu' })
    .getByRole('button', { name: 'Sign out' })
    .click()
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  await page.getByRole('tab', { name: 'Versions' }).click()
  await expect(
    page
      .getByRole('region', { name: 'Versions' })
      .getByRole('button', { name: 'Publish', exact: true }),
  ).toBeDisabled()
  const editorPublish = await request.post(`/api/v1/layouts/${draft.layout.id}/publish`, {
    headers: { Authorization: authFor('editor') },
    data: { note: 'editor must not publish' },
  })
  expect(editorPublish.status()).toBe(403)
  expect((await new Api(request, 'admin').draft())!.layout.status).toBe('DRAFT')
})
