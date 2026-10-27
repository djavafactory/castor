import { expect, test, type Page } from '@playwright/test'
import { expectNoA11yViolations } from './helpers'

/** Signs in with the API mocked, so these tests need no backend. */
async function signIn(page: Page) {
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      json: { username: 'ada', displayName: 'Ada Lovelace', roles: ['EDITOR', 'VIEWER'] },
    }),
  )
  await page.route('**/api/v1/auth/demo-users', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/version', (route) =>
    route.fulfill({
      json: { version: '0.1.0', commit: 'abc', buildTime: '', environment: 'test' },
    }),
  )
  await page.route('**/api/v1/ports/*', (route) =>
    route.fulfill({
      json: {
        code: 'westports',
        name: 'Westports',
        city: 'Port Klang',
        timeZone: 'Asia/Kuala_Lumpur',
        utcOffset: '+08:00',
        origin: { lon: 101.3066304, lat: 2.9534057 },
        measureSrid: 3380,
        frameRotDeg: -120.299,
        extent: { type: 'Polygon', coordinates: [] },
        landExtent: null,
        quayLine: null,
        synthetic: true,
        publishedLayoutVersionId: null,
        berthCount: 0,
        attribution: '',
        dataSources: [],
      },
    }),
  )
  await page.route('**/api/v1/ports/*/layouts/draft', (route) =>
    route.fulfill({
      json: {
        layout: {
          id: '00000000-0000-0000-0000-000000000002',
          versionNo: 2,
          status: 'DRAFT',
          basedOnVersionNo: 1,
          createdBy: 'ada',
          createdAt: '2026-10-27T01:00:00Z',
          updatedBy: 'ada',
          updatedAt: '2026-10-27T01:00:00Z',
        },
        features: { type: 'FeatureCollection', features: [] },
      },
    }),
  )
  await page.goto('/login')
  await page.getByLabel('Username').fill('ada')
  await page.getByLabel('Password', { exact: true }).fill('secret')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('toolbar', { name: 'Tools' })).toBeVisible()
}

const strip = (page: Page) => page.getByRole('toolbar', { name: 'Tools' })

test('every control of the strip has a tooltip and an accessible name', async ({ page }) => {
  await signIn(page)
  const buttons = strip(page).getByRole('button')
  const count = await buttons.count()
  expect(count).toBeGreaterThanOrEqual(11)
  for (let i = 0; i < count; i++) {
    const button = buttons.nth(i)
    await expect(button).toHaveAttribute('title', /.+/)
    expect((await button.getAttribute('aria-label')) ?? (await button.innerText())).toBeTruthy()
  }
  await expect(page.getByRole('group', { name: 'Zoom and rotate' })).toBeVisible()
  await expect(page.getByRole('group', { name: 'Show in 3D' })).toBeVisible()
})

test('keyboard shortcuts: camera presets, panel, labels and help', async ({ page }) => {
  await signIn(page)

  await page.keyboard.press('4')
  await expect(page.getByRole('button', { name: 'Camera position: Quay' })).toBeVisible()
  await page.keyboard.press('5')
  await expect(page.getByRole('button', { name: 'Camera position: Ship' })).toBeVisible()

  await expect(page.getByLabel('Right panel')).toBeVisible()
  await page.keyboard.press('p')
  await expect(page.getByLabel('Right panel')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Show panel' })).toBeVisible()
  await page.keyboard.press('p')
  await expect(page.getByLabel('Right panel')).toBeVisible()

  const labels = page.getByRole('button', { name: 'Labels' })
  await expect(labels).toHaveAttribute('aria-pressed', 'true')
  await page.keyboard.press('l')
  await expect(labels).toHaveAttribute('aria-pressed', 'false')

  await page.keyboard.press('?')
  const help = page.getByRole('heading', { name: 'Help and legend' })
  await expect(help).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Keyboard' })).toBeVisible()
  await expect(page.getByText('Imported verified')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(help).toHaveCount(0)
})

test('shortcuts are ignored with Ctrl held', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'All layers' }).click()
  // The layers popover has no text field; use the page itself: Ctrl+L must not toggle the labels
  await page.keyboard.press('Escape')
  await page.keyboard.press('Control+l')
  await expect(page.getByRole('button', { name: 'Labels' })).toHaveAttribute('aria-pressed', 'true')
})

test('Fullscreen flips its text, or says it is not available in this window', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'Fullscreen' }).click()
  const flipped = page.getByRole('button', { name: 'Exit fullscreen' })
  const refused = page.getByText('Fullscreen is not available in this window')
  await expect(flipped.or(refused)).toBeVisible()
})

test('All layers: isolate one layer, then Show all', async ({ page }) => {
  await signIn(page)
  await page.getByRole('button', { name: 'All layers' }).click()
  await expect(page.getByRole('heading', { name: 'Layers' })).toBeVisible()
  const isolate = page.getByRole('button', { name: 'Isolate Berths' })
  await isolate.click()
  await expect(isolate).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Show all' }).click()
  await expect(isolate).toHaveAttribute('aria-pressed', 'false')
  await expectNoA11yViolations(page)
})

test('Design: A hides the palette, the strip has no Fullscreen and offers Verify accuracy', async ({
  page,
}) => {
  await signIn(page)
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByLabel('Asset palette')).toBeVisible()
  await page.keyboard.press('a')
  await expect(page.getByLabel('Asset palette')).toHaveCount(0)
  await page.keyboard.press('a')
  await expect(page.getByLabel('Asset palette')).toBeVisible()

  await expect(page.getByRole('button', { name: 'Fullscreen' })).toHaveCount(0)
  // The envelope and accuracy switches live in the bottom strip
  await expect(
    page.getByLabel('Status').getByRole('switch', { name: 'Envelope boxes' }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'More' }).click()
  await expect(page.getByRole('button', { name: 'Verify accuracy' })).toBeVisible()
})
