import { expect, test, type Page } from '@playwright/test'
import {
  Api,
  drawOnMap,
  needsStack,
  openDesign,
  openTab,
  PlanMap,
  placeOnMap,
  signIn,
  signOut,
} from '../helpers/acceptance'

// JOIN-04 part 2: the Phase 1 finish line. A reviewer signs in as admin and builds the demo port from an empty layout
// with the designer only, fixes one deliberate rule problem, verifies accuracy, publishes and sees it in Monitor.
// Runs against the dev stack (E2E_BASE_URL). It publishes a version, so it puts the seeded layout back at the end.
test.skip(!process.env.E2E_BASE_URL, needsStack)

test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

// Local metres in the quay frame (+x along the quay, +y inland, the quay edge is y = 0). Everything sits on the land
// strip around the port origin, which is where searching "Westports" centres the map.
// The draw readout covers the top left of the map while a shape is drawn, so the layout sits right of it.
const WAREHOUSE: [number, number][] = [
  [-70, 150],
  [-10, 150],
  [-10, 200],
  [40, 200],
  [40, 330],
  [-70, 330],
]
const LAYDOWN: [number, number][] = [
  [80, 180],
  [200, 180],
  [200, 330],
  [80, 330],
]
const ZONE: [number, number][] = [
  [360, 170],
  [440, 170],
  [440, 260],
  [360, 260],
]
// The deliberate mistake: a second exclusion zone drawn over the silo, which no zone allows
const SILO_AT: [number, number] = [230, 90]
const SILO_ZONE: [number, number][] = [
  [200, 60],
  [260, 60],
  [260, 120],
  [200, 120],
]

let keepVersion = 0

async function toast(page: Page, text: string | RegExp) {
  await expect(page.getByText(text).first()).toBeVisible({ timeout: 15_000 })
}

test.afterAll(async ({ request }) => {
  // Put the seeded Westports layout back as the live version so the rest of the suite sees what it expects:
  // restoring v1 keeps every asset id, and a new published version is the only trace of this run.
  const api = new Api(request, 'admin')
  const versions = await api.versions()
  const seed = versions.find((v) => v.versionNo === 1)
  if (!seed || keepVersion === 0) return
  const restored = await request.post(`/api/v1/layouts/${seed.id}/restore`, {
    headers: { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` },
    data: { discardDraft: true },
  })
  expect(restored.ok(), await restored.text()).toBe(true)
  const draft = (await api.draft())!
  const published = await request.post(`/api/v1/layouts/${draft.layout.id}/publish`, {
    headers: { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` },
    data: { note: 'Westports demo layout (restored after the acceptance run)' },
  })
  expect(published.ok(), await published.text()).toBe(true)
})

test('an admin builds the demo port from a blank layout, fixes an issue, verifies and publishes', async ({
  page,
  request,
}) => {
  const api = new Api(request, 'admin')

  await signIn(page, 'admin')
  await openDesign(page, '2D')

  // Versions -> New blank layout
  await openTab(page, 'Versions')
  await page.getByRole('button', { name: 'New blank layout' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Start blank' }).click()
  await expect(page.getByText(/0 assets/)).toBeVisible({ timeout: 20_000 })
  const blank = (await api.draft())!
  expect(blank.features.features).toHaveLength(0)

  // Search "Westports"
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill('Westports')
  await expect(page.getByRole('option').first()).toContainText('Westports Malaysia')
  await search.press('Enter')
  await expect(page.getByTestId('map-pin')).toBeVisible()
  await openTab(page, 'Inspector')
  const map = await new PlanMap(page).settle()

  // Terminal boundary, warehouse polygon (6 vertices) and laydown yard
  const terminal = await placeOnMap(page, map, 'TERMINAL_BOUNDARY', 'terminal boundary', [250, 250])
  expect(terminal.properties.type).toBe('TERMINAL_BOUNDARY')
  const warehouse = await drawOnMap(page, map, 'WAREHOUSE_POLY', 'warehouse', WAREHOUSE)
  expect((warehouse.geometry.coordinates as unknown[][])[0]).toHaveLength(WAREHOUSE.length + 1)
  const laydown = await drawOnMap(page, map, 'LAYDOWN_YARD', 'laydown', LAYDOWN)
  expect(laydown.properties.type).toBe('LAYDOWN_YARD')

  // Two berths on the quay, a rail quay crane on a berth, a mobile harbour crane and a silo
  const berth1 = await placeOnMap(page, map, 'BERTH', 'berth', [50, 4])
  const berth2 = await placeOnMap(page, map, 'BERTH', 'berth', [300, 4])
  expect(berth1.properties.type).toBe('BERTH')
  expect(berth2.id).not.toBe(berth1.id)
  const quayCrane = await placeOnMap(page, map, 'QUAY_CRANE_RAIL', 'quay crane', [50, 12])
  expect(quayCrane.properties.type).toBe('QUAY_CRANE_RAIL')
  await placeOnMap(page, map, 'MOBILE_HARBOUR_CRANE', 'mobile harbour', [330, 60])
  const silo = await placeOnMap(page, map, 'SILO', 'silo', SILO_AT)
  expect(silo.properties.type).toBe('SILO')

  // An exclusion zone that allows tanks, and a tank inside it
  const zone = await drawOnMap(page, map, 'EXCLUSION_ZONE', 'exclusion', ZONE)
  const inspector = page.getByRole('region', { name: 'Asset properties' })
  await expect(inspector).toBeVisible()
  const saved = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${zone.id}`),
  )
  await page
    .getByRole('group', { name: 'Allowed types inside' })
    .getByRole('checkbox', { name: 'Liquid tank' })
    .click()
  expect((await saved).status()).toBe(200)
  await expect(
    page
      .getByRole('group', { name: 'Allowed types inside' })
      .getByRole('checkbox', { name: 'Liquid tank' }),
  ).toBeChecked()
  const tank = await placeOnMap(page, map, 'LIQUID_TANK', 'liquid tank', [400, 215])
  expect(tank.properties.type).toBe('LIQUID_TANK')

  // The deliberate mistake: a zone over the silo. Issues names it, and the silo is moved out of the zone
  await drawOnMap(page, map, 'EXCLUSION_ZONE', 'exclusion', SILO_ZONE)
  await openTab(page, /^Issues/)
  const issues = page.getByRole('region', { name: 'Issues' })
  await issues.getByRole('button', { name: 'Check again' }).click()
  await expect(issues.getByText(/1 error \(blocks publishing\)/)).toBeVisible({ timeout: 20_000 })
  const row = issues.getByRole('button', { name: /Inside safety exclusion zone/ })
  await expect(row).toBeVisible()
  // Publishing is blocked until it is fixed
  await openTab(page, 'Versions')
  await page.getByPlaceholder('What changed').fill('Westports demo: built from a blank layout')
  await page
    .getByRole('region', { name: 'Versions' })
    .getByRole('button', { name: 'Publish', exact: true })
    .click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)

  // Fix: pick the silo from the issue and drag it out of the zone
  await openTab(page, /^Issues/)
  await row.click()
  await map.settle()
  const grab = map.at(...SILO_AT)
  await page.mouse.move(grab.x, grab.y)
  const moved = page.waitForResponse(
    (r) => r.request().method() === 'PATCH' && r.url().endsWith(`/assets/${silo.id}`),
  )
  await map.drag(SILO_AT, [SILO_AT[0], SILO_AT[1] + 230])
  expect((await moved).status()).toBe(200)
  await page
    .getByRole('region', { name: 'Issues' })
    .getByRole('button', { name: 'Check again' })
    .click()
  await expect(page.getByRole('region', { name: 'Issues' }).getByText(/0 errors/)).toBeVisible({
    timeout: 20_000,
  })

  // Verify accuracy (More menu), the result is the Accuracy check section of Issues
  await page.getByRole('button', { name: 'More', exact: true }).click()
  await page.getByRole('button', { name: 'Verify accuracy' }).click()
  await expect(page.getByRole('region', { name: 'Accuracy check' })).toContainText(
    'control point',
    { timeout: 20_000 },
  )

  // Publish with a note; Monitor shows the new version
  await openTab(page, 'Versions')
  const before = (await api.versions()).filter((v) => v.status !== 'DRAFT').length
  await page
    .getByRole('region', { name: 'Versions' })
    .getByRole('button', { name: 'Publish', exact: true })
    .click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Publish' }).click()
  await toast(page, /Version \d+ published\. Monitor shows it now\./)
  const versions = await api.versions()
  const live = versions.find((v) => v.status === 'PUBLISHED')!
  keepVersion = live.versionNo
  expect(versions.filter((v) => v.status !== 'DRAFT').length).toBe(before + 1)
  const published = await api.current()
  expect(published.features.features).toHaveLength(blank.features.features.length + 11)

  await page.getByRole('button', { name: 'Monitor' }).click()
  await expect(
    page.getByText(new RegExp(`^Published v${live.versionNo} · .* by admin$`)),
  ).toBeVisible({ timeout: 20_000 })

  // Sign out
  await signOut(page, 'admin')
})

test('login, the 3D port loads, and signing out leaves no credentials behind', async ({ page }) => {
  await signIn(page, 'admin')
  await expect(page.locator('[data-testid="port-scene"] canvas')).toBeVisible()

  await signOut(page, 'admin')
  const left = (await page.evaluate(`(async () => {
    const dbs = (await indexedDB.databases?.()) ?? []
    return {
      local: { ...localStorage },
      session: { ...sessionStorage },
      cookie: document.cookie,
      databases: dbs.map((d) => d.name),
    }
  })()`)) as { local: object; session: object; cookie: string; databases: unknown[] }
  // Only the layout of the screen (panels, view mode) is kept for the tab: no user, password or token anywhere
  expect(Object.keys(left.local)).toEqual([])
  expect(Object.keys(left.session)).toEqual(['castor.ui'])
  const everything = JSON.stringify([left.local, left.session, left.cookie])
  expect(everything).not.toMatch(/admin|Basic|Bearer|password|token|YWRtaW46/i)
  expect(left.cookie).toBe('')
  expect(left.databases).toEqual([])
  // Back on the login page nothing is signed in: the app asks again
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
})
