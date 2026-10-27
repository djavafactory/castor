import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL), which resets and seeds the published Westports layout on every start.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function signInAsViewer(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('viewer')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible()
}

/** Moves over the stage until an asset of the layout (not an OSM building) is under the pointer. */
async function hoverAnAsset(page: Page): Promise<{ x: number; y: number }> {
  const box = (await page.getByTestId('port-scene').boundingBox())!
  const tooltip = page.getByRole('tooltip')
  // The terminal is a long strip across the lower middle of the stage; scan that band in small steps.
  for (let y = box.y + box.height * 0.54; y < box.y + box.height * 0.64; y += 5) {
    for (let x = box.x + 4; x < box.x + box.width; x += 5) {
      await page.mouse.move(x, y)
      if ((await tooltip.count()) > 0 && !(await tooltip.innerText()).startsWith('Building ')) {
        return { x, y }
      }
    }
  }
  throw new Error('no layout asset found under the pointer')
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
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()
})

test('a viewer can select an asset and read its details, with nothing to edit', async ({
  page,
}) => {
  test.setTimeout(90_000)
  await signInAsViewer(page)
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()

  const at = await hoverAnAsset(page)
  await page.mouse.click(at.x, at.y)

  const details = page.getByRole('region', { name: 'Asset details' })
  await expect(details).toBeVisible()
  await expect(details.getByText(/published layout, read-only/)).toBeVisible()
  await expect(details.getByRole('button')).toHaveCount(0)
  await expect(details.getByRole('textbox')).toHaveCount(0)

  // No palette, no editing tabs, no live data
  await expect(page.getByLabel('Asset palette')).toHaveCount(0)
  await expect(page.getByRole('tab', { name: /Inspector|Issues|Versions/ })).toHaveCount(0)
  await expect(page.getByText(/KPI|berth schedule|connected/i)).toHaveCount(0)
})

test('a viewer is told that Design is for editors', async ({ page }) => {
  await signInAsViewer(page)
  await page.getByRole('button', { name: 'Design' }).click()
  await expect(page.getByRole('button', { name: 'Monitor' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})
