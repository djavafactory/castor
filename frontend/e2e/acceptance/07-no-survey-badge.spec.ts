import { expect, test } from '@playwright/test'
import {
  Api,
  drawOnMap,
  freeSpot,
  goToLocal,
  needsStack,
  openDesign,
  openTab,
  signIn,
  keepDraftClean,
} from '../helpers/acceptance'

// PRD 19.1 item 8, on the dev stack: a shape traced on the satellite imagery is APPROXIMATE, says so with the NO SURVEY
// badge (and a warning in Issues), and the badge goes when the accuracy is truthfully raised.
test.skip(!process.env.E2E_BASE_URL, needsStack)

keepDraftClean('editor')

test('an imagery-traced shape is marked NO SURVEY and Approximate', async ({ page, request }) => {
  const api = new Api(request, 'editor')
  await signIn(page, 'editor')
  await openDesign(page, '2D')
  const [x, y] = await freeSpot(api)
  const map = await goToLocal(page, api, x, y)
  await expect(page.getByLabel('Basemap')).toHaveValue('sentinel2')

  const drawn = await drawOnMap(page, map, 'LAYDOWN_YARD', 'laydown', [
    [x + 20, y - 80],
    [x + 120, y - 80],
    [x + 120, y - 20],
    [x + 20, y - 20],
  ])
  expect(drawn.properties.provenance).toMatchObject({
    source: 'SENTINEL2_EOX',
    accuracy: 'APPROXIMATE',
    note: 'Traced from 10 m satellite imagery: approximate, not a survey',
  })

  // The badge sits in the header of the Inspector, with its explanation
  await openTab(page, 'Inspector')
  const badge = page.getByText('NO SURVEY', { exact: true })
  await expect(badge).toBeVisible()
  await expect(badge).toHaveAttribute(
    'title',
    'Only a rough imagery-based or synthetic position exists',
  )
  const provenance = page.getByRole('region', { name: 'Provenance and accuracy' })
  await expect(provenance.getByLabel('Accuracy')).toContainText('Approximate')

  // Issues counts it among the shapes that have no survey
  await openTab(page, /^Issues/)
  await page
    .getByRole('region', { name: 'Issues' })
    .getByRole('button', { name: 'Check again' })
    .click()
  await expect(
    page.getByRole('region', { name: 'Issues' }).getByText(/\d+ shapes have no survey/),
  ).toBeVisible({ timeout: 20_000 })

  // The accuracy outlines of the 2D and 3D views are a switch of the bottom strip
  const outlines = page.getByRole('checkbox', { name: 'Accuracy' })
  await outlines.check()
  await expect(outlines).toBeChecked()
  await outlines.uncheck()

  // Raising the accuracy takes the badge away, and the stored provenance says so
  await openTab(page, 'Inspector')
  const saved = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${drawn.id}`),
  )
  await provenance.getByLabel('Accuracy').selectOption({ label: 'Surveyed' })
  expect((await saved).status()).toBe(200)
  await expect(badge).toHaveCount(0)
  const { asset } = await api.asset(drawn.properties.name)
  expect(asset.properties.provenance.accuracy).toBe('SURVEYED')
})
