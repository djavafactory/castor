import { expect, test } from '@playwright/test'
import { cameraPreset, openDesign, signInAsDemo, viewButton } from './helpers/stack'
import { expectScreen, stableNetwork } from './helpers/visual'

// Screenshot baselines (JOIN-03) against the dev stack, which resets to the same seed on start. The baselines are
// made in the Playwright Linux image (see the "Visual baselines" workflow), never on a dev machine.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

// Reduced motion: no camera fly, no water, no hover pulse, no rotation
test.use({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' })

test.beforeEach(async ({ page }) => {
  test.setTimeout(120_000)
  await stableNetwork(page)
})

test('login', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await expectScreen(page, 'login')
})

test.describe('Monitor', () => {
  for (const preset of ['Overview', 'Top', 'Quay']) {
    test(preset, async ({ page }) => {
      await signInAsDemo(page, 'viewer')
      await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()
      await cameraPreset(page, preset)
      await page.mouse.move(0, 0)
      await expectScreen(page, `monitor-${preset.toLowerCase()}`)
    })
  }
})

test.describe('Design', () => {
  test.beforeEach(async ({ page }) => {
    await signInAsDemo(page, 'editor')
    await openDesign(page)
  })

  for (const view of ['2D', '3D', 'Split'] as const) {
    test(view, async ({ page }) => {
      await viewButton(page, view).click()
      if (view !== '2D') await cameraPreset(page, 'Overview')
      await page.mouse.move(0, 0)
      await expectScreen(page, `design-${view.toLowerCase()}`)
    })
  }

  test('inspector open', async ({ page }) => {
    await viewButton(page, 'Split').click()
    const search = page.getByRole('combobox', { name: 'Search places' })
    await search.fill('Berth')
    await expect(page.getByRole('option').first()).toBeVisible()
    await search.press('Enter')
    await expect(page.getByRole('tab', { name: 'Inspector' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // the 3D gizmo of the selection is up: both views show it
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
    await search.blur()
    await page.mouse.move(0, 0)
    await expectScreen(page, 'design-inspector')
  })

  test('drawing in progress', async ({ page }) => {
    await viewButton(page, '2D').click()
    await expect(page.getByTestId('scale-bar')).toBeVisible({ timeout: 20_000 })
    await page.getByTestId('asset-palette').getByRole('searchbox').fill('laydown')
    await page.getByTestId('palette-tile-LAYDOWN_YARD').click()
    const box = (await page.getByRole('region', { name: '2D map' }).boundingBox())!
    // Two corners placed; the pointer holds the third, so the rubber band and the readout show
    await page.mouse.click(box.x + box.width * 0.4, box.y + box.height * 0.4)
    await page.mouse.click(box.x + box.width * 0.6, box.y + box.height * 0.4)
    await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.6)
    await expectScreen(page, 'design-drawing')
    await page.keyboard.press('Escape')
  })
})
