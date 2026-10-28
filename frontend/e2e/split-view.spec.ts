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

const view = (page: Page, name: '2D' | '3D' | 'Split') =>
  page.getByRole('group', { name: 'View' }).getByRole('button', { name })

test('Split is the default, 2D-only drops the 3D canvas and the choice survives a reload', async ({
  page,
}) => {
  await openDesign(page)
  await expect(page.getByRole('main', { name: 'Design stage, split' })).toBeVisible()
  await expect(page.getByRole('region', { name: '2D map' })).toBeVisible()
  // two canvases side by side: the 2D plan and the 3D scene
  await expect(page.getByRole('main').locator('canvas')).toHaveCount(2)

  await view(page, '2D').click()
  await expect(page.getByRole('main').locator('canvas')).toHaveCount(1)

  await page.reload()
  await expect(page.getByRole('main', { name: 'Design stage, 2d' })).toBeVisible({
    timeout: 20_000,
  })
  await view(page, 'Split').click()
})

test('an asset selected on the 2D map is selected in 3D too', async ({ page }) => {
  await openDesign(page)
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill('Berth')
  await expect(page.getByRole('option').first()).toBeVisible()
  await search.press('Enter')
  // the 3D gizmo of the selection is up: the 3D view shows the same selection
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            (
              globalThis as unknown as { __castorGizmo?: { grab(p: string): unknown } }
            ).__castorGizmo?.grab('length') ?? null,
        ),
      { timeout: 15_000 },
    )
    .not.toBeNull()
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
  const entry = page.getByText(/^Measured /).first()
  const text = (await entry.textContent()) ?? ''
  const match = /^Measured (\d+\.\d\d) m \(geodesic (\d+\.\d\d) m\), bearing \d+\.\d deg$/.exec(
    text,
  )
  expect(match, text).not.toBeNull()
  expect(Math.abs(Number(match![1]) - Number(match![2]))).toBeLessThan(0.1)

  // Esc closes the history; the button again leaves the tool
  await page.keyboard.press('Escape')
  await measure.click()
  await expect(measure).toHaveAttribute('aria-pressed', 'false')
})

test('the issues button opens the Issues tab', async ({ page }) => {
  await openDesign(page)
  const issues = page
    .getByLabel('Status')
    .getByRole('button', { name: /^\d+ errors?, \d+ warnings?$/ })
  await expect(issues).toBeVisible({ timeout: 20_000 })
  await issues.click()
  await expect(page.getByRole('tab', { name: /Issues/ })).toHaveAttribute('aria-selected', 'true')
})
