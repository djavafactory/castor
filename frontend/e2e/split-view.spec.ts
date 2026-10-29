import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): the real backend and its seeded Westports draft. Nothing is saved.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function openDesign(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByText(/Draft v\d+/)).toBeVisible({ timeout: 20_000 })
}

const view = (page: Page, name: '2D' | 'Split') =>
  page.getByRole('group', { name: 'View' }).getByRole('button', { name })

test('Split is the default, 2D-only drops the 3D half and the choice survives a reload', async ({
  page,
}) => {
  await openDesign(page)
  await expect(page.getByRole('main', { name: 'Design stage, split' })).toBeVisible()
  await expect(page.getByRole('region', { name: '2D map' })).toBeVisible()
  // the e2e browser has no WebGL: next to the 2D plan the 3D half shows its notice instead of a scene
  const no3d = page.getByText(/^3D view not available/)
  await expect(no3d).toBeVisible()

  await view(page, '2D').click()
  await expect(no3d).toHaveCount(0)

  // A reload asks for the sign-in again (credentials stay in memory); the view of the tab is kept
  await page.reload()
  await openDesign(page)
  await expect(page.getByRole('main', { name: 'Design stage, 2d' })).toBeVisible()
  await view(page, 'Split').click()
})

test('measures a distance on the 2D map, checked against the geodesic', async ({ page }) => {
  await openDesign(page)
  await view(page, '2D').click()
  const status = page.getByLabel('Status')
  const measure = status.getByRole('button', { name: 'Measure' })
  await measure.click()
  await expect(measure).toHaveAttribute('aria-pressed', 'true')

  const box = (await page.getByRole('region', { name: '2D map' }).boundingBox())!
  await page.mouse.click(box.x + box.width * 0.3, box.y + box.height * 0.5)
  await page.mouse.click(box.x + box.width * 0.7, box.y + box.height * 0.5)

  await status.getByRole('button', { name: /^History \(\d+\)$/ }).click()
  const entry = page
    .getByRole('dialog')
    .getByText(/^Measured \d/)
    .first()
  const text = (await entry.textContent()) ?? ''
  const match = /^Measured (\d+\.\d\d) m \(geodesic (\d+\.\d\d) m\), bearing \d+\.\d deg$/.exec(
    text,
  )
  expect(match, text).not.toBeNull()
  expect(Math.abs(Number(match![1]) - Number(match![2]))).toBeLessThan(0.1)

  // Esc closes the history and leaves the tool
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(measure).toHaveAttribute('aria-pressed', 'false')
})

test('the issues button opens the Issues tab', async ({ page }) => {
  await openDesign(page)
  const issues = page
    .getByLabel('Status')
    .getByRole('button', { name: /^\d+ errors?, \d+ warnings?$/ })
  await expect(issues).toBeVisible({ timeout: 20_000 })
  // The strip fits the window (1280 px): it never widens the page or pushes the issues button out
  await expect(issues).toBeInViewport({ ratio: 1 })
  expect(
    await page.evaluate(
      () =>
        (globalThis as unknown as { document: { documentElement: { scrollWidth: number } } })
          .document.documentElement.scrollWidth,
    ),
  ).toBeLessThanOrEqual(page.viewportSize()!.width)
  await issues.click()
  await expect(page.getByRole('tab', { name: /Issues/ })).toHaveAttribute('aria-selected', 'true')
})
