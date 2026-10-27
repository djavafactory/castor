import { expect, test } from '@playwright/test'
import { login } from './helpers'

// Needs the real backend: the port record is read from GET /api/v1/ports/{code}, validated by zod and shown.
test.skip(
  !process.env.E2E_BASE_URL,
  'Set E2E_BASE_URL to a running stack (docker compose --profile dev)',
)

test('the header shows the port served by the API', async ({ page }) => {
  await login(page)
  await expect(page.getByText('Westports', { exact: true })).toBeVisible()
  await expect(page.getByText('SYNTHETIC')).toBeVisible()
})
