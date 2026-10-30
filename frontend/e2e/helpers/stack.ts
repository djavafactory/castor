import { expect, type Page } from '@playwright/test'

/** Signs in with a demo user of the dev stack and waits for the Monitor stage. */
export async function signInAsDemo(page: Page, user: 'viewer' | 'editor' | 'admin') {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption(user)
  await page.getByRole('button', { name: 'Sign in' }).click()
  // The first sign-in after the stack starts is slow: it loads the layout and the buildings
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
}

/** Switches a signed-in editor to Design and waits for the draft. */
export async function openDesign(page: Page) {
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByText(/Draft v\d+/)).toBeVisible({ timeout: 20_000 })
}

/** The work area fills the window whatever its content: the Design bottom strip ends at the bottom edge. */
export async function expectStripAtBottom(page: Page) {
  const strip = (await page.getByLabel('Status').boundingBox())!
  expect(strip.y + strip.height).toBeCloseTo(page.viewportSize()!.height, 0)
}
