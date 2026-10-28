import { expect, test, type Page } from '@playwright/test'
import { expectStripAtBottom, openDesign, signInAsDemo } from './helpers/stack'
import { expectScreen, stableNetwork } from './helpers/visual'

// The laptop layout (JOIN-03) against the dev stack: layout checks and a baseline per width.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

test.use({ reducedMotion: 'reduce' })

test.beforeEach(async ({ page }) => {
  test.setTimeout(120_000)
  await stableNetwork(page)
})

const pageWidth = (page: Page) =>
  page.evaluate(
    () =>
      (globalThis as unknown as { document: { documentElement: { scrollWidth: number } } }).document
        .documentElement.scrollWidth,
  )

async function widthOf(page: Page, label: string) {
  return (await page.getByLabel(label, { exact: true }).boundingBox())!.width
}

const WIDTHS = [
  { width: 1024, height: 768, panel: 300, palette: 220, icons: true },
  { width: 1280, height: 800, panel: 348, palette: 260, icons: false },
  { width: 1440, height: 900, panel: 348, palette: 260, icons: false },
  { width: 1920, height: 1080, panel: 348, palette: 260, icons: false },
]

for (const w of WIDTHS) {
  test.describe(`${w.width} px`, () => {
    test.use({ viewport: { width: w.width, height: w.height } })

    test('Monitor fits, with the panel and the tool strip for this width', async ({ page }) => {
      await signInAsDemo(page, 'viewer')
      expect(await pageWidth(page)).toBeLessThanOrEqual(w.width)
      expect(await widthOf(page, 'Right panel')).toBeCloseTo(w.panel, 0)
      await expect(page.getByTestId('narrow-notice')).toBeHidden()
      await expect(page.getByTitle(/^Synthetic layout/)).toBeVisible()
      // icon only at <= 1100 px: the button is about as wide as its icon
      const panelButton = (await page.getByRole('button', { name: 'Hide panel' }).boundingBox())!
      if (w.icons) expect(panelButton.width).toBeLessThan(40)
      else expect(panelButton.width).toBeGreaterThan(60)
      await page.mouse.move(0, 0)
      await expectScreen(page, `monitor-${w.width}`)
    })

    test('Design fits, and the bottom strip keeps every tool in view', async ({ page }) => {
      await signInAsDemo(page, 'editor')
      await openDesign(page)
      expect(await pageWidth(page)).toBeLessThanOrEqual(w.width)
      expect(await widthOf(page, 'Asset palette')).toBeCloseTo(w.palette, 0)
      await expectStripAtBottom(page)
      const status = page.getByLabel('Status')
      await expect(status.getByRole('button', { name: /errors?, \d+ warnings?$/ })).toBeInViewport({
        ratio: 1,
        timeout: 20_000,
      })
      await expect(status.getByRole('checkbox', { name: 'Envelope' })).toBeInViewport({ ratio: 1 })
      await page.mouse.move(0, 0)
      await expectScreen(page, `design-${w.width}`)
    })
  })
}

test.describe('below 1024 px', () => {
  test.use({ viewport: { width: 900, height: 700 } })

  test('Monitor shows the notice, the panel as a drawer and no SYNTHETIC badge', async ({
    page,
  }) => {
    await signInAsDemo(page, 'viewer')
    const note = page.getByTestId('narrow-notice')
    await expect(note).toHaveText('Castor is designed for screens at least 1024 px wide.')
    // directly under the tool strip
    const strip = (await page.getByRole('toolbar', { name: 'Tools' }).boundingBox())!
    expect((await note.boundingBox())!.y).toBeCloseTo(strip.y + strip.height, 0)
    await expect(page.getByTitle(/^Synthetic layout/)).toBeHidden()

    // the drawer floats over the stage: the stage keeps the full width
    expect(await widthOf(page, 'Right panel')).toBeCloseTo(340, 0)
    expect((await page.getByRole('main').boundingBox())!.width).toBeCloseTo(900, 0)
    await page.mouse.move(0, 0)
    await expectScreen(page, 'monitor-900')

    await page.getByRole('button', { name: 'Close panel' }).click()
    await expect(page.getByLabel('Right panel')).toHaveCount(0)
    await page.getByRole('button', { name: 'Show panel' }).click()
    await expect(page.getByLabel('Right panel')).toBeVisible()
  })

  test('Design adds that editing needs 1024 px', async ({ page }) => {
    await signInAsDemo(page, 'editor')
    await openDesign(page)
    await expect(page.getByTestId('narrow-notice')).toContainText(
      'Editing the layout needs a window at least 1024 px wide',
    )
  })
})
