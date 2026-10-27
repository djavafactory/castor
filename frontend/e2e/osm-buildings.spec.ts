import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL), which resets and seeds Westports on every start.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function signInAsViewer(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('viewer')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible()
}

/** Moves over the stage until a building is under the pointer; returns where. */
async function hoverABuilding(page: Page): Promise<{ x: number; y: number }> {
  const box = (await page.getByTestId('port-scene').boundingBox())!
  // The port is a long strip across the middle of the stage; scan that band in small steps.
  for (let y = box.y + box.height * 0.48; y < box.y + box.height * 0.66; y += 6) {
    for (let x = box.x + 4; x < box.x + box.width; x += 6) {
      await page.mouse.move(x, y)
      if ((await page.getByRole('tooltip').count()) > 0) return { x, y }
    }
  }
  throw new Error('no building found under the pointer')
}

test('the OSM buildings show in 3D; hover and click give read-only details', async ({ page }) => {
  await signInAsViewer(page)
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()

  const at = await hoverABuilding(page)
  await expect(page.getByRole('tooltip')).toHaveText(
    /^Building \S+, \d+ m2, \d+(\.\d)? m \(OpenStreetMap\)$/,
  )

  await page.mouse.click(at.x, at.y)
  const details = page.getByRole('region', { name: 'Building details' })
  await expect(details).toBeVisible()
  await expect(details.getByText('Reference feature, read-only')).toBeVisible()
  await expect(details.getByText('OpenStreetMap (ODbL)')).toBeVisible()
  await expect(details.getByRole('button')).toHaveCount(0)
  await expect(details.getByRole('textbox')).toHaveCount(0)
})

test('Buildings (OSM) is a layer in All layers and can be switched off', async ({ page }) => {
  await signInAsViewer(page)
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()
  const at = await hoverABuilding(page)

  await page.getByRole('button', { name: 'All layers' }).click()
  const layer = page.getByRole('checkbox', { name: 'Buildings (OSM)' })
  await expect(layer).toBeChecked()
  await layer.uncheck()
  await page.keyboard.press('Escape')

  await page.mouse.move(at.x + 1, at.y + 1)
  await page.mouse.move(at.x, at.y)
  await expect(page.getByRole('tooltip')).toHaveCount(0)
})
