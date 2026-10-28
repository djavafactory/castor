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
  await expect(page.getByTestId('scale-bar')).toContainText('200 m', { timeout: 20_000 })
  // the draft is loaded once the footer counts its assets: the ghost needs it
  await expect(page.getByText(/Draft v\d+/)).toBeVisible({ timeout: 20_000 })
}

test('the palette lists the groups, searches and hides with the Assets button and A', async ({
  page,
}) => {
  await openDesign(page)
  const palette = page.getByTestId('asset-palette')
  await expect(palette.getByRole('heading', { level: 3 })).toHaveText([
    'Cranes & cargo handling',
    'Material handling',
    'Storage & infrastructure',
    'Transport & logistics',
    'Berth & marine facilities',
    'Roads',
    'RoRo',
    'Zones & safety',
  ])
  await expect(palette.getByRole('button', { name: 'Cranes & cargo handling' })).toHaveAttribute(
    'aria-expanded',
    'true',
  )
  await expect(palette.getByRole('button', { name: 'Roads' })).toHaveAttribute(
    'aria-expanded',
    'false',
  )

  await palette.getByRole('searchbox', { name: 'Search assets' }).fill('zzz')
  await expect(page.getByText('No asset matches.')).toBeVisible()
  await palette.getByRole('searchbox', { name: 'Search assets' }).fill('FORKLIFT')
  await expect(palette.getByTestId('palette-tile-FORKLIFT')).toBeVisible()

  const assets = page.getByRole('button', { name: 'Assets' })
  await expect(assets).toHaveAttribute('aria-pressed', 'true')
  await assets.click()
  await expect(page.getByLabel('Asset palette')).toHaveCount(0)
  await page.keyboard.press('a')
  await expect(page.getByLabel('Asset palette')).toBeVisible()
})

test('placing a forklift saves it at once and it is still there, at the same spot, after a reload', async ({
  page,
}) => {
  await openDesign(page)
  await page.getByTestId('asset-palette').getByRole('searchbox').fill('forklift')
  const tile = page.getByTestId('palette-tile-FORKLIFT')
  await tile.click()
  await expect(tile).toHaveAttribute('aria-pressed', 'true')

  // over the middle of the map (a container yard of the seeded terminal) the ghost is valid
  const map = page.getByRole('img', { name: /Plan of the port/ })
  const box = (await map.boundingBox())!
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 }
  await page.mouse.move(at.x - 30, at.y - 10)
  await page.mouse.move(at.x, at.y)
  await expect(page.getByTestId('placement-pill')).toContainText('Click to place. R rotates')

  const saved = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url()),
  )
  await page.mouse.click(at.x, at.y)
  const response = await saved
  expect(response.status()).toBe(201)
  const feature = (await response.json()).feature
  const anchor = feature.properties.anchor as [number, number]
  const code = feature.properties.code as string
  expect(feature.properties.modelRev).toBeGreaterThanOrEqual(1)

  // the tool ends after one placement, the asset is selected
  await expect(tile).toHaveAttribute('aria-pressed', 'false')

  // a reload ends the session (credentials live in memory only): sign in again and open Design
  const draft = page.waitForResponse(
    (r) => r.request().method() === 'GET' && /\/layouts\/draft$/.test(r.url()),
  )
  await openDesign(page)
  const reloaded = await (await draft).json()
  const again = reloaded.features.features.find(
    (f: { properties: { code: string } }) => f.properties.code === code,
  )
  expect(again).toBeDefined()
  expect(Math.abs(again.properties.anchor[0] - anchor[0])).toBeLessThan(1e-7)
  expect(Math.abs(again.properties.anchor[1] - anchor[1])).toBeLessThan(1e-7)

  // tidy: remove it again so the shared draft goes back to the seeded layout
  const auth = 'Basic ' + Buffer.from('editor:editor').toString('base64')
  const layoutId = reloaded.layout.id as string
  const del = await page.request.delete(`/api/v1/layouts/${layoutId}/assets/${again.id}`, {
    headers: { Authorization: auth, 'If-Match': `"${again.properties.rowVersion}"` },
  })
  expect(del.status()).toBe(204)
})

test('a spot in the water is refused with the reason in the pill and nothing is saved', async ({
  page,
}) => {
  await openDesign(page)
  await page.getByTestId('asset-palette').getByRole('searchbox').fill('forklift')
  await page.getByTestId('palette-tile-FORKLIFT').click()
  const map = page.getByRole('img', { name: /Plan of the port/ })
  const box = (await map.boundingBox())!

  let posted = false
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url())) posted = true
  })
  // the bottom of the map is the sea: the ghost there is red, with the reason, and a click saves nothing
  let reason: string | null = null
  for (const [fx, fy] of [
    [0.15, 0.8],
    [0.85, 0.75],
    [0.2, 0.85],
  ] as const) {
    const x = box.x + box.width * fx
    const y = box.y + box.height * fy
    await page.mouse.move(x - 5, y)
    await page.mouse.move(x, y)
    const pill = page.getByTestId('placement-pill')
    await expect(pill).toBeVisible()
    if ((await pill.getAttribute('data-valid')) === 'false') {
      reason = (await pill.textContent())!.trim()
      await page.mouse.click(x, y)
      break
    }
  }
  expect(reason).toMatch(/water|land|outside|Inside|Overlaps/i)
  await expect(page.getByText(`Cannot place here: ${reason}`)).toBeVisible()
  expect(posted).toBe(false)
})
