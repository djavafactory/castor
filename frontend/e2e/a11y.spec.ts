import { expect, test, type Page } from '@playwright/test'
import { expectNoSeriousA11yViolations } from './helpers'
import { openDesign, signInAsDemo } from './helpers/stack'

// Runs against the dev stack (E2E_BASE_URL): the real shell, layout and draft. Nothing is saved.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

test('the login page has no serious or critical issue', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await expectNoSeriousA11yViolations(page)
})

test('Monitor has no serious or critical issue', async ({ page }) => {
  await signInAsDemo(page, 'viewer')
  await expectNoSeriousA11yViolations(page)
})

test('Design has no serious or critical issue, with the palette, the strip and each panel tab', async ({
  page,
}) => {
  await signInAsDemo(page, 'editor')
  await openDesign(page)
  await expect(page.getByLabel('Asset palette')).toBeVisible()
  for (const tab of ['Inspector', 'Issues', 'Versions']) {
    await page.getByRole('tab', { name: new RegExp(`^${tab}`) }).click()
    await expectNoSeriousA11yViolations(page)
  }
})

/** The aria-label of the focused element, or its text. */
async function focusedName(page: Page): Promise<string> {
  const focused = page.locator(':focus')
  if ((await focused.count()) === 0) return ''
  return ((await focused.getAttribute('aria-label')) ?? (await focused.textContent()) ?? '').trim()
}

test('every shell control is reachable with Tab and shows a focus ring', async ({ page }) => {
  await signInAsDemo(page, 'viewer')
  const reached = new Set<string>()
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press('Tab')
    reached.add(await focusedName(page))
  }
  for (const name of ['Monitor', 'Design', 'Zoom in', 'Rotate', 'Labels', 'More', 'Details']) {
    expect([...reached], name).toContain(name)
  }

  // the global ring: 2 px signal outline on :focus-visible (keyboard focus)
  const zoomIn = page.getByRole('button', { name: 'Zoom in' })
  await page.getByRole('button', { name: 'Zoom out' }).focus()
  await page.keyboard.press('Tab')
  await expect(zoomIn).toBeFocused()
  await expect(zoomIn).toHaveCSS('outline-style', 'solid')
  await expect(zoomIn).toHaveCSS('outline-width', '2px')
})

test('the panel tabs are a real tablist; arrows move between them', async ({ page }) => {
  await signInAsDemo(page, 'viewer')
  const details = page.getByRole('tab', { name: 'Details' })
  await details.focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('tab', { name: 'Catalogue' })).toBeFocused()
  await expect(page.getByRole('tab', { name: 'Catalogue' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'tab-catalogue')
})

test('toggles say they are pressed and menus say they open', async ({ page }) => {
  await signInAsDemo(page, 'viewer')
  const labels = page.getByRole('button', { name: 'Labels' })
  const before = await labels.getAttribute('aria-pressed')
  await labels.click()
  await expect(labels).toHaveAttribute('aria-pressed', before === 'true' ? 'false' : 'true')

  const user = page.getByRole('button', { name: /viewer/ })
  await expect(user).toHaveAttribute('aria-haspopup', 'dialog')
  await user.click()
  await expect(user).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByRole('dialog', { name: 'User menu' })).toBeVisible()
  await page.keyboard.press('Escape')

  // An open Radix menu hides the rest of the page from screen readers, so the trigger is found by CSS here
  const camera = page.locator('button[aria-label^="Camera position:"]')
  await expect(camera).toHaveAttribute('aria-haspopup', 'menu')
  await camera.click()
  await expect(page.getByRole('menu')).toBeVisible()
  await expect(camera).toHaveAttribute('aria-expanded', 'true')
})

test.describe('with reduced motion', () => {
  test.use({ reducedMotion: 'reduce' })

  test('Rotate is refused with a toast that screen readers announce', async ({ page }) => {
    await signInAsDemo(page, 'viewer')
    const rotate = page.getByRole('button', { name: 'Rotate' })
    await rotate.click()
    await expect(rotate).toHaveAttribute('aria-pressed', 'false')
    // Sonner's toaster is a polite live region
    const toasts = page.getByRole('region', { name: /Notifications/ })
    await expect(toasts).toHaveAttribute('aria-live', 'polite')
    await expect(toasts).toContainText('Auto-rotate is off because reduced motion is on')
  })
})
