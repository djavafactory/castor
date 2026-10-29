import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): the real backend, its seeded Westports layout and the real rule check.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function openDesign(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: '2D' }).click()
  await expect(page.getByText(/Draft v\d+/)).toBeVisible({ timeout: 20_000 })
}

test('a placed warehouse is edited in the Inspector, saved at once and still has its values after a reload', async ({
  page,
}) => {
  await openDesign(page)

  // place a warehouse over the middle of the map, as place.spec does, and keep the response for its code
  await page.getByTestId('asset-palette').getByRole('searchbox').fill('warehouse')
  const tile = page.getByTestId('palette-tile-WAREHOUSE')
  await tile.click()
  // a warehouse is a 80 x 40 m box: the first spot of the map where the ghost is valid (not over another shape)
  const map = page.getByRole('img', { name: /Plan of the port/ })
  const box = (await map.boundingBox())!
  const pill = page.getByTestId('placement-pill')
  let at: { x: number; y: number } | null = null
  for (const fx of [0.5, 0.3, 0.7, 0.2, 0.8]) {
    for (const fy of [0.3, 0.5, 0.7, 0.2, 0.8]) {
      const spot = { x: box.x + box.width * fx, y: box.y + box.height * fy }
      await page.mouse.move(spot.x, spot.y)
      if (await pill.isVisible()) {
        if (/Click to place/.test((await pill.textContent()) ?? '')) {
          at = spot
          break
        }
      }
    }
    if (at) break
  }
  expect(at).not.toBeNull()
  const spot = at!
  const placed = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url()),
  )
  await page.mouse.click(spot.x, spot.y)
  const feature = (await (await placed).json()).feature
  const id = feature.id as string
  const auth = 'Basic ' + Buffer.from('editor:editor').toString('base64')
  const current = await page.request.get('/api/v1/ports/WESTPORTS/layouts/draft', {
    headers: { Authorization: auth },
  })
  const layoutId = (await current.json()).layout.id as string

  // the Inspector shows the asset that was just placed: rename it, Enter saves one change
  const inspector = page.getByRole('region', { name: 'Asset properties' })
  await expect(inspector).toBeVisible()
  const name = page.getByRole('textbox', { name: 'Name' })
  const renamed = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${id}`),
  )
  await name.fill('Inspector shed')
  await name.press('Enter')
  expect((await renamed).status()).toBe(200)
  await expect(page.getByRole('heading', { name: 'Inspector shed' })).toBeVisible()

  // a choice saves at once: the door side faces east
  const doorSide = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${id}`),
  )
  await page.getByRole('combobox', { name: 'Door side' }).selectOption('EAST')
  expect((await doorSide).status()).toBe(200)

  // Check and save says everything is saved, with the largest rounding change
  await page.getByRole('button', { name: 'Check and save' }).click()
  await expect(page.getByText(/^All changes saved\./)).toBeVisible()

  // a reload ends the session: after it, the stored draft has the values the Inspector saved
  await openDesign(page)
  const reloaded = await (
    await page.request.get('/api/v1/ports/WESTPORTS/layouts/draft', {
      headers: { Authorization: auth },
    })
  ).json()
  const again = reloaded.features.features.find((f: { id: string }) => f.id === id)
  expect(again.properties.name).toBe('Inspector shed')
  expect(again.properties.details.warehouse.doorSide).toBe('EAST')

  // tidy: remove it again so the shared draft goes back to the seeded layout
  const del = await page.request.delete(`/api/v1/layouts/${layoutId}/assets/${id}`, {
    headers: { Authorization: auth, 'If-Match': `"${again.properties.rowVersion}"` },
  })
  expect(del.status()).toBe(204)
})
