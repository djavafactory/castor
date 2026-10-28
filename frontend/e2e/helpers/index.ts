import { expect, type Page } from '@playwright/test'

export { expectNoA11yViolations, expectNoSeriousA11yViolations } from './axe'

export type Role = 'ADMIN' | 'DESIGNER' | 'VIEWER'

export interface Point {
  lon: number
  lat: number
}

function credentialsFor(role: Role): { username: string; password: string } {
  // E2E_<ROLE>_USERNAME / E2E_<ROLE>_PASSWORD, falling back to E2E_USERNAME / E2E_PASSWORD
  const username = process.env[`E2E_${role}_USERNAME`] ?? process.env.E2E_USERNAME
  const password = process.env[`E2E_${role}_PASSWORD`] ?? process.env.E2E_PASSWORD
  if (!username || !password) {
    throw new Error(
      `Set E2E_${role}_USERNAME and E2E_${role}_PASSWORD (or E2E_USERNAME and E2E_PASSWORD)`,
    )
  }
  return { username, password }
}

/** Signs in through the login form as the given role and waits for the shell. */
export async function login(page: Page, role: Role = 'ADMIN') {
  const { username, password } = credentialsFor(role)
  await page.goto('/login')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).not.toHaveURL(/\/login$/)
}

/** Opens the designer from the shell. */
export async function enterDesign(page: Page) {
  await page.getByRole('link', { name: 'Design' }).click()
  await expect(page.getByTestId('design-canvas')).toBeVisible()
}

/** Wraps the canvas point lookup: the app exposes the map-to-screen projection as window.__castor.project. */
async function screenPoint(page: Page, { lon, lat }: Point) {
  const point = await page.evaluate(
    ([x, y]) => (globalThis as unknown as CastorTestHooks).__castor.project(x, y),
    [lon, lat],
  )
  const box = await page.getByTestId('design-canvas').boundingBox()
  if (!box) throw new Error('design canvas is not on screen')
  return { x: box.x + point.x, y: box.y + point.y }
}

interface CastorTestHooks {
  __castor: { project(lon: number, lat: number): { x: number; y: number } }
}

/** Picks an asset type in the palette and drops it at a coordinate. */
export async function placeAsset(page: Page, type: string, lon: number, lat: number) {
  await page.getByRole('button', { name: type, exact: true }).click()
  const { x, y } = await screenPoint(page, { lon, lat })
  await page.mouse.click(x, y)
}

/** Draws a polygon vertex by vertex; the last vertex is closed with a double click. */
export async function drawPolygon(page: Page, points: Point[]) {
  if (points.length < 3) throw new Error('a polygon needs at least 3 points')
  await page.getByRole('button', { name: 'Draw polygon' }).click()
  for (const [i, p] of points.entries()) {
    const { x, y } = await screenPoint(page, p)
    if (i === points.length - 1) await page.mouse.dblclick(x, y)
    else await page.mouse.click(x, y)
  }
}
