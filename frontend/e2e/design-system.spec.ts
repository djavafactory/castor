import { expect, test, type Page } from '@playwright/test'
import { expectNoA11yViolations } from './helpers'

/** Signs in through the login form with the API mocked, so these tests need no backend. */
async function signIn(page: Page) {
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      json: { username: 'ada', displayName: 'Ada Lovelace', roles: ['EDITOR', 'VIEWER'] },
    }),
  )
  await page.route('**/api/v1/auth/demo-users', (route) => route.fulfill({ json: [] }))
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
  // Entering Design loads the team's draft
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
  await expect(page).not.toHaveURL(/\/login$/)
}

/** The session lives in memory only, so a reload would sign out: move inside the running app instead. */
async function openDesignSystem(page: Page) {
  await page.evaluate(() => {
    const w = globalThis as unknown as {
      history: { pushState(d: object, t: string, u: string): void }
      dispatchEvent(e: unknown): void
      PopStateEvent: new (type: string) => unknown
    }
    w.history.pushState({}, '', '/design-system')
    w.dispatchEvent(new w.PopStateEvent('popstate'))
  })
}

test('the design system renders every component and loads no external fonts', async ({ page }) => {
  const external: string[] = []
  page.on('request', (r) => {
    const url = new URL(r.url())
    if (url.hostname !== 'localhost') external.push(r.url())
  })
  await signIn(page)
  await openDesignSystem(page)
  await expect(page.getByRole('heading', { name: 'Design system' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Buttons' })).toBeVisible()
  expect(external.filter((u) => /font/i.test(u))).toEqual([])
  await expectNoA11yViolations(page)
})

for (const size of [
  { width: 1440, height: 900 },
  { width: 1280, height: 800 },
]) {
  test(`Monitor and Design keep their panel sizes at ${size.width}x${size.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(size)
    await signIn(page)
    await expect(page.getByLabel('Right panel')).toBeVisible()
    await expect(page.getByLabel('Right panel')).toHaveJSProperty('offsetWidth', 348)

    await page.getByRole('button', { name: 'Design' }).click()
    await expect(page.getByLabel('Asset palette')).toHaveJSProperty('offsetWidth', 260)
    await expect(page.getByLabel('Status')).toHaveJSProperty('offsetHeight', 32)
  })
}
