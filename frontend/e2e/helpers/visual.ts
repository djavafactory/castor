import {
  expect,
  type Locator,
  type Page,
  type PageAssertionsToHaveScreenshotOptions,
} from '@playwright/test'

/**
 * Makes a page repeatable for screenshots (JOIN-03): requests to other hosts (the satellite tiles) are aborted so the
 * land is the plain colour. The rest comes from the spec (reduced motion) and the config (no CSS animations, 0.5 %).
 */
export async function stableNetwork(page: Page) {
  const origin = new URL(process.env.E2E_BASE_URL!).origin
  await page.route('**/*', (route) =>
    new URL(route.request().url()).origin === origin ? route.continue() : route.abort(),
  )
}

/** Texts that change from run to run: publish and change times, version stamps and the pointer position. */
export function volatile(page: Page): Locator[] {
  return [
    page.getByText(/^Published v\d+/),
    page.getByText(/^Castor v\d/),
    page.getByText(/Draft v\d+/),
    page.getByTestId('cursor-readout'),
  ]
}

/** Waits until nothing loads, then compares the page with its baseline. */
export async function expectScreen(
  page: Page,
  name: string,
  options: PageAssertionsToHaveScreenshotOptions = {},
) {
  await page.waitForLoadState('networkidle')
  await expect(page).toHaveScreenshot(`${name}.png`, { mask: volatile(page), ...options })
}
