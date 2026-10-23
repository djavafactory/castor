import { expect, test } from '@playwright/test'
import { expectNoA11yViolations } from './helpers'

test('unauthenticated visitors land on the login page', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
})

test('the login page has no accessibility violations', async ({ page }) => {
  await page.goto('/login')
  await expectNoA11yViolations(page)
})
