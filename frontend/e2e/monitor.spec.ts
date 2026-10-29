import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL), which resets and seeds the published Westports layout on every start.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function signInAsViewer(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('viewer')
  await page.getByRole('button', { name: 'Sign in' }).click()
  // The first sign-in after the stack starts is slow: it loads the layout and the buildings
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
}

test('Monitor is the start mode and shows the published version next to the port name', async ({
  page,
}) => {
  await signInAsViewer(page)

  await expect(page.getByRole('button', { name: 'Monitor' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  const version = page.getByText(/^Published v\d+ · \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2} by \S+$/)
  await expect(version).toBeVisible()
})

test('a viewer is told that Design is for editors', async ({ page }) => {
  await signInAsViewer(page)
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByRole('button', { name: 'Monitor' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})
