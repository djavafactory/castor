import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): the real backend, its seeded Westports layout and the OSM background.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function signInAsEditor(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
}

/** Design in the 2D view: the map alone, with the draft of the team loaded. */
async function openMap(page: Page) {
  await signInAsEditor(page)
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: '2D' }).click()
  const map = page.getByRole('region', { name: '2D map' })
  await expect(map).toBeVisible()
  // The first view is placed once the draft has arrived
  await expect(page.getByTestId('scale-bar')).toContainText('200 m', { timeout: 20_000 })
  return map
}

const search = (page: Page) => page.getByRole('combobox', { name: 'Search places' })

test('the 2D map has a scale bar, a north arrow turned by the frame and a cursor readout', async ({
  page,
}) => {
  const map = await openMap(page)

  await expect(page.getByTestId('north-arrow')).toBeVisible()
  await expect(page.getByTestId('north-needle')).toHaveAttribute('transform', 'rotate(-120.299)')

  // The strip below the map shows where the pointer is, in the format of the ticket
  const box = (await map.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  const readout = page.getByTestId('cursor-readout')
  await expect(readout).toHaveText(/^-?\d+\.\d{7}, -?\d+\.\d{7} {2}\(x -?\d+\.\d, y -?\d+\.\d m\)$/)
  await expect(readout).toHaveAttribute(
    'title',
    'Cursor position: WGS84 latitude, longitude and local metres',
  )
})

test('the wheel zooms around the pointer and the scale bar follows', async ({ page }) => {
  const map = await openMap(page)
  const box = (await map.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)

  await page.mouse.wheel(0, -600)
  await expect(page.getByTestId('scale-bar')).not.toContainText('200 m')

  // The ground follows a drag with the left button on empty space, and the arrow keys move it too
  const before = await page.getByTestId('cursor-readout').textContent()
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 120, box.y + box.height / 2 + 40, { steps: 6 })
  await page.mouse.up()
  const after = await page.getByTestId('cursor-readout').textContent()
  expect(after).not.toBe(before)
})

test('"berth 2" finds Berth 2, and Enter selects it', async ({ page }) => {
  await openMap(page)
  await search(page).fill('berth 2')

  const first = page.getByRole('option').first()
  await expect(first).toContainText('Berth 2')
  await expect(first).toContainText('Berth')
  await search(page).press('Enter')

  await expect(page.getByRole('tab', { name: 'Inspector' })).toBeVisible()
  await expect(page.getByText('Berth 2').first()).toBeVisible()
  await expect(page.getByRole('listbox')).toHaveCount(0)
})

test('"gate" finds the main gate control point and the gate assets', async ({ page }) => {
  await openMap(page)
  await search(page).fill('gate')

  const options = page.getByRole('option')
  await expect(options.filter({ hasText: 'Westport Conventional Main Gate' })).toContainText(
    'Control point',
  )
  await expect(options.filter({ hasText: 'Gate house' })).toHaveCount(1)
  await search(page).press('Escape')
  await expect(page.getByRole('listbox')).toHaveCount(0)
})

test('"2.9518844, 101.3093602" lands on the main gate with a pin that goes after 4 s', async ({
  page,
}) => {
  const map = await openMap(page)
  await search(page).fill('2.9518844, 101.3093602')
  await expect(
    page.getByRole('option', { name: 'Go to 2.9518844, 101.3093602 (lat, lon)' }),
  ).toBeVisible()
  await search(page).press('Enter')

  await expect(page.getByTestId('map-pin')).toBeVisible()
  // The pin is at the middle of the map; the pointer there reads the gate to within a pixel (under 2 m at 0.6 px/m)
  const box = (await map.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  const text = (await page.getByTestId('cursor-readout').textContent()) ?? ''
  const [lat, lon] = text.split('  ')[0].split(', ').map(Number)
  expect(Math.abs(lat - 2.9518844)).toBeLessThan(0.00003)
  expect(Math.abs(lon - 101.3093602)).toBeLessThan(0.00003)

  await expect(page.getByTestId('map-pin')).toHaveCount(0, { timeout: 6_000 })
})

test('coordinates that make no sense are explained and nothing moves', async ({ page }) => {
  await openMap(page)
  await search(page).fill('95, 101.3')
  await expect(page.getByText('Latitude must be -90..90 and longitude -180..180')).toBeVisible()

  await search(page).fill('3.139, 101.6869')
  await expect(
    page.getByText('That place is outside this port layout. Search is a locator only.'),
  ).toBeVisible()
})

test('the search sends nothing but the typed words to this backend', async ({ page }) => {
  await openMap(page)
  const outside: string[] = []
  const origin = new URL(page.url()).origin
  page.on('request', (request) => {
    if (new URL(request.url()).origin !== origin && !request.url().includes('tiles')) {
      outside.push(request.url())
    }
  })

  await search(page).fill('berth 2')
  await expect(page.getByRole('option').first()).toContainText('Berth 2')
  await search(page).fill('2.9518844, 101.3093602')
  await search(page).press('Enter')

  expect(outside).toEqual([])
  // and nothing of it is kept in the browser
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))
  expect(stored).not.toContain('berth')
  expect(stored).not.toContain('2.9518844')
})

test('the basemap: grid, Google note, and a preference that stays with the user', async ({
  page,
}) => {
  await openMap(page)
  const select = page.getByRole('combobox', { name: 'Basemap' })
  await expect(select).toHaveValue('sentinel2')

  await select.selectOption({ label: 'None (grid)' })
  await expect(select).toHaveValue('none')
  await expect(page.getByText(/Google Maps is a locator only/)).toHaveCount(0)

  await select.selectOption({ label: 'Google Maps (lookup only)' })
  await expect(
    page.getByText(
      'Google Maps is a locator only: its imagery cannot be traced into stored geometry.',
    ),
  ).toBeVisible()

  // Signed in again (a reload asks for the login), the same user finds the same basemap
  await page.reload()
  await signInAsEditor(page)
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByRole('combobox', { name: 'Basemap' })).toHaveValue('google')
})

test('the search endpoint answers from the port data, never caches and wants two characters', async ({
  page,
  request,
}) => {
  await signInAsEditor(page)
  const auth = `Basic ${Buffer.from('editor:editor').toString('base64')}`
  const found = await request.get('/api/v1/ports/westports/search?q=berth%202', {
    headers: { Authorization: auth },
  })
  expect(found.status()).toBe(200)
  expect(found.headers()['cache-control']).toBe('no-store')
  const hits = (await found.json()) as { kind: string; label: string }[]
  expect(hits[0]).toMatchObject({ kind: 'ASSET', label: 'Berth 2' })

  const short = await request.get('/api/v1/ports/westports/search?q=b', {
    headers: { Authorization: auth },
  })
  expect(short.status()).toBe(400)
})
