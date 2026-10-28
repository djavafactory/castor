import { expect, type Page } from '@playwright/test'

/** Signs in with a demo user of the dev stack and waits for the Monitor stage. */
export async function signInAsDemo(page: Page, user: 'viewer' | 'editor') {
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

export const viewButton = (page: Page, name: '2D' | '3D' | 'Split') =>
  page.getByRole('group', { name: 'View' }).getByRole('button', { name })

/** Picks a camera preset from the camera menu (reduced motion: no fly, the view jumps). */
export async function cameraPreset(page: Page, label: string) {
  await page.getByRole('button', { name: /^Camera position:/ }).click()
  await page.getByRole('menuitem', { name: new RegExp(`^${label}`) }).click()
  await expect(page.getByRole('button', { name: `Camera position: ${label}` })).toBeVisible()
}
