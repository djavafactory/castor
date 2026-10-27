import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): the real backend, its seeded Westports layout and the real rule check.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function openIssues(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: '2D' }).click()
  await expect(page.getByTestId('scale-bar')).toContainText('200 m', { timeout: 20_000 })
  await page.getByRole('tab', { name: /^Issues/ }).click()
}

test('the seeded layout has no errors, one no-survey warning, and a row that zooms to its first asset', async ({
  page,
}) => {
  await openIssues(page)

  const issues = page.getByRole('region', { name: 'Issues' })
  await expect(issues.getByText('0 errors (block publishing), 1 warning')).toBeVisible({
    timeout: 20_000,
  })
  const row = issues.getByRole('button', { name: /301 shapes have no survey/ })
  await expect(row).toBeVisible()
  // the red count pill on the tab counts errors and warnings
  await expect(page.getByRole('tab', { name: /^Issues\s*1$/ })).toBeVisible()

  await row.click()

  // the list stays open, the map moved to the first asset
  await expect(page.getByRole('tab', { name: /^Issues/ })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByTestId('scale-bar')).not.toContainText('200 m')
})

test('checking again asks the server again', async ({ page }) => {
  await openIssues(page)
  const issues = page.getByRole('region', { name: 'Issues' })
  await expect(issues.getByText(/errors? \(blocks? publishing\)/)).toBeVisible({ timeout: 20_000 })

  const asked = page.waitForRequest(
    (r) => r.method() === 'POST' && /\/api\/v1\/layouts\/[^/]+\/validate$/.test(r.url()),
  )
  await issues.getByRole('button', { name: 'Check again' }).click()

  expect((await asked).method()).toBe('POST')
})
