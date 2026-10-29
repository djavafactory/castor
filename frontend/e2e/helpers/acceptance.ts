import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { createFrame, type QuayFrame } from '../../src/lib/geo/frame'

// Helpers of the Phase 1 acceptance specs (JOIN-04). They drive the real dev stack through the real screens; the API is
// used only to set up and tidy, and to read back what the screens saved.

export type DemoUser = 'viewer' | 'editor' | 'publisher' | 'admin'

/** The demo accounts of the dev stack use their user name as the password. */
export const authFor = (user: DemoUser) =>
  `Basic ${Buffer.from(`${user}:${user}`).toString('base64')}`

export const needsStack = 'needs the running dev stack: set E2E_BASE_URL'

export async function signIn(page: Page, user: DemoUser) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption(user)
  await page.getByRole('button', { name: 'Sign in' }).click()
  // The first sign-in after the stack starts is slow: it loads the layout and the buildings
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 30_000 })
}

/** Opens the user menu (the button named after the signed-in user) and signs out. */
export async function signOut(page: Page, user: DemoUser) {
  await page.getByRole('button', { name: user, exact: true }).click()
  await page
    .getByRole('dialog', { name: 'User menu' })
    .getByRole('button', { name: 'Sign out' })
    .click()
  await expect(page).toHaveURL(/\/login$/)
}

export async function openDesign(page: Page, view: '2D' | 'Split' = '2D') {
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: view }).click()
  await expect(page.getByText(/Draft v\d+|Blank draft|Draft/).first()).toBeVisible({
    timeout: 20_000,
  })
}

export async function openTab(page: Page, name: string | RegExp) {
  await page.getByRole('tab', { name }).click()
}

// ---------------------------------------------------------------- the 2D map in local metres

interface Calibration {
  /** Pixels per metre. */
  k: number
  /** Page pixel of local (0, 0). */
  ox: number
  oy: number
}

const READOUT = /\(x (-?\d+\.\d), y (-?\d+\.\d) m\)/

/**
 * The 2D map is drawn in the quay frame with y up and one scale in both directions. The cursor readout names the local
 * metres under the pointer, so two pointer positions give the whole mapping from local metres to page pixels. That
 * keeps the specs independent of how the view was placed.
 */
export class PlanMap {
  private cal: Calibration | null = null

  readonly page: Page

  constructor(page: Page) {
    this.page = page
  }

  get canvas(): Locator {
    return this.page.getByRole('img', { name: /Plan of the port/ })
  }

  private async readLocal(px: number, py: number) {
    await this.page.mouse.move(px, py)
    const readout = this.page.getByTestId('cursor-readout')
    const read = () =>
      readout.textContent().then((t) => READOUT.exec(t ?? '')) as Promise<RegExpExecArray | null>
    await expect.poll(async () => (await read()) !== null).toBe(true)
    const match = (await read())!
    return { x: Number(match[1]), y: Number(match[2]) }
  }

  /** Calibrates until two runs in a row agree: the view may still be flying to a search result. */
  async settle() {
    let last = ''
    await expect
      .poll(
        async () => {
          await this.calibrate()
          const c = this.need()
          const now = [c.k, c.ox, c.oy].map((n) => n.toFixed(1)).join('|')
          const same = now === last
          last = now
          return same
        },
        { timeout: 15_000, intervals: [300] },
      )
      .toBe(true)
    return this
  }

  /** Call again after the view moved (zoom, pan, fly to a search result). */
  async calibrate() {
    const box = (await this.canvas.boundingBox())!
    const ax = box.x + box.width * 0.3
    const ay = box.y + box.height * 0.3
    const bx = box.x + box.width * 0.7
    const by = box.y + box.height * 0.7
    await this.readLocal(ax - 3, ay - 3) // wake the readout so the second sample is settled
    const a = await this.readLocal(ax, ay)
    const b = await this.readLocal(bx, by)
    const k = (bx - ax) / (b.x - a.x)
    this.cal = { k, ox: ax - a.x * k, oy: ay + a.y * k }
    return this
  }

  get scale() {
    return this.need().k
  }

  private need(): Calibration {
    if (!this.cal) throw new Error('call calibrate() first')
    return this.cal
  }

  /** The page position of a local point (metres). */
  at(x: number, y: number) {
    const c = this.need()
    return { x: c.ox + x * c.k, y: c.oy - y * c.k }
  }

  /** The local point (metres) under a page position. */
  local(px: number, py: number) {
    const c = this.need()
    return { x: (px - c.ox) / c.k, y: (c.oy - py) / c.k }
  }

  /** The local point at the middle of the canvas. */
  async centre() {
    const box = (await this.canvas.boundingBox())!
    return this.local(box.x + box.width / 2, box.y + box.height / 2)
  }

  async click(x: number, y: number) {
    const p = this.at(x, y)
    await this.page.mouse.move(p.x - 4, p.y - 2)
    await this.page.mouse.move(p.x, p.y)
    await this.page.mouse.click(p.x, p.y)
  }

  /** Draws a polygon vertex by vertex (local metres) and closes it with Enter. */
  async drawRing(points: [number, number][]) {
    for (const [x, y] of points) await this.click(x, y)
    await this.page.keyboard.press('Enter')
  }

  async drag(from: [number, number], to: [number, number]) {
    const a = this.at(...from)
    const b = this.at(...to)
    await this.page.mouse.move(a.x, a.y)
    await this.page.mouse.down()
    await this.page.mouse.move((a.x + b.x) / 2, (a.y + b.y) / 2, { steps: 4 })
    await this.page.mouse.move(b.x, b.y, { steps: 4 })
    await this.page.mouse.up()
  }
}

// ---------------------------------------------------------------- the API, for setup and read-back

export interface StoredAsset {
  id: string
  geometry: { type: string; coordinates: unknown }
  properties: Record<string, any> & { code: string; name: string; rowVersion: number } // eslint-disable-line @typescript-eslint/no-explicit-any
}

export interface Draft {
  layout: { id: string; versionNo: number; status: string }
  features: { features: StoredAsset[] }
}

export class Api {
  private readonly request: APIRequestContext
  private readonly user: DemoUser
  private readonly port: string

  constructor(request: APIRequestContext, user: DemoUser = 'admin', port = 'westports') {
    this.request = request
    this.user = user
    this.port = port
  }

  private headers(extra: Record<string, string> = {}) {
    return { Authorization: authFor(this.user), ...extra }
  }

  async draft(): Promise<Draft | null> {
    const r = await this.request.get(`/api/v1/ports/${this.port}/layouts/draft`, {
      headers: this.headers(),
    })
    if (r.status() === 404) return null
    expect(r.ok(), await r.text()).toBe(true)
    return (await r.json()) as Draft
  }

  /** The open draft; when there is none yet (a publish just used it up) one is started from the published version. */
  async ensureDraft(): Promise<Draft> {
    const existing = await this.draft()
    if (existing) return existing
    const r = await this.request.post(`/api/v1/ports/${this.port}/layouts`, {
      headers: this.headers(),
      data: { basedOn: 'published' },
    })
    expect(r.status(), await r.text()).toBe(201)
    return (await this.draft())!
  }

  async current(): Promise<Draft> {
    const r = await this.request.get(`/api/v1/ports/${this.port}/layouts/current`, {
      headers: this.headers(),
    })
    expect(r.ok(), await r.text()).toBe(true)
    return (await r.json()) as Draft
  }

  async versions(): Promise<{ id: string; versionNo: number; status: string }[]> {
    const r = await this.request.get(`/api/v1/ports/${this.port}/layouts`, {
      headers: this.headers(),
    })
    expect(r.ok(), await r.text()).toBe(true)
    return await r.json()
  }

  /** Any version of the layout by id, with its features. */
  async layout(id: string): Promise<Draft> {
    const r = await this.request.get(`/api/v1/layouts/${id}`, { headers: this.headers() })
    expect(r.ok(), await r.text()).toBe(true)
    return (await r.json()) as Draft
  }

  /** The quay frame of the port, for converting between local metres and WGS84 like the app does. */
  async frame(): Promise<QuayFrame> {
    const r = await this.request.get(`/api/v1/ports/${this.port}`, { headers: this.headers() })
    expect(r.ok(), await r.text()).toBe(true)
    const p = await r.json()
    return createFrame({ lon0: p.origin.lon, lat0: p.origin.lat, rotDeg: p.frameRotDeg })
  }

  /** POST asset into a draft; returns the raw response so a test can look at a refusal too. */
  async create(layoutId: string, body: Record<string, unknown>) {
    return this.request.post(`/api/v1/layouts/${layoutId}/assets`, {
      headers: this.headers(),
      data: body,
    })
  }

  async asset(name: string): Promise<{ layoutId: string; asset: StoredAsset }> {
    const draft = (await this.draft())!
    const asset = draft.features.features.find((f) => f.properties.name === name)
    if (!asset) throw new Error(`${name} is not in the draft`)
    return { layoutId: draft.layout.id, asset }
  }

  async remove(layoutId: string, asset: StoredAsset) {
    const r = await this.request.delete(`/api/v1/layouts/${layoutId}/assets/${asset.id}`, {
      headers: this.headers({ 'If-Match': `"${asset.properties.rowVersion}"` }),
    })
    expect(r.status(), await r.text()).toBe(204)
  }

  /** Deletes the assets of the draft that are not in `keep` (ids): puts a shared draft back as it was. */
  async removeAllExcept(keep: ReadonlySet<string>) {
    const draft = await this.draft()
    if (!draft) return
    for (const f of draft.features.features) {
      if (!keep.has(f.id)) await this.remove(draft.layout.id, f)
    }
  }
}

/** A distance between two local points. */
export const dist = (a: { x: number; y: number }, b: { x: number; y: number }) =>
  Math.hypot(a.x - b.x, a.y - b.y)

/** Great-circle-free metres between two WGS84 points close together (equirectangular, good to a millimetre at 100 m). */
export function metresBetween(a: [number, number], b: [number, number]) {
  const R = 6378137
  const lat = ((a[1] + b[1]) / 2) * (Math.PI / 180)
  const dx = (b[0] - a[0]) * (Math.PI / 180) * R * Math.cos(lat)
  const dy = (b[1] - a[1]) * (Math.PI / 180) * R
  return Math.hypot(dx, dy)
}

// ---------------------------------------------------------------- placing and drawing on the 2D map

const isCreate = (r: { request(): { method(): string }; url(): string }) =>
  r.request().method() === 'POST' && /\/layouts\/[^/]+\/assets$/.test(r.url())

/** Picks a palette tile by its type code (the search narrows the list so the tile is on screen). */
export async function pickTile(page: Page, code: string, search: string) {
  const palette = page.getByTestId('asset-palette')
  await palette.getByRole('searchbox', { name: 'Search assets' }).fill(search)
  const tile = page.getByTestId(`palette-tile-${code}`)
  await tile.click()
  await expect(tile).toHaveAttribute('aria-pressed', 'true')
}

/** Places a catalogue asset with the palette and a click at local (x, y); returns the saved feature. */
export async function placeOnMap(
  page: Page,
  map: PlanMap,
  code: string,
  search: string,
  at: [number, number],
): Promise<StoredAsset> {
  await pickTile(page, code, search)
  const p = map.at(...at)
  await page.mouse.move(p.x - 6, p.y - 3)
  await page.mouse.move(p.x, p.y)
  await expect(page.getByTestId('placement-pill')).toContainText('Click to place', {
    timeout: 10_000,
  })
  const saved = page.waitForResponse(isCreate, { timeout: 15_000 })
  await page.mouse.click(p.x, p.y)
  const response = await saved
  expect(response.status(), await response.text()).toBe(201)
  return (await response.json()).feature as StoredAsset
}

/** Draws a polygon type with the palette tile and clicks the ring (local metres); returns the saved feature. */
export async function drawOnMap(
  page: Page,
  map: PlanMap,
  code: string,
  search: string,
  ring: [number, number][],
): Promise<StoredAsset> {
  await pickTile(page, code, search)
  const saved = page.waitForResponse(isCreate, { timeout: 15_000 })
  await map.drawRing(ring)
  const response = await saved
  expect(response.status(), await response.text()).toBe(201)
  return (await response.json()).feature as StoredAsset
}

// ---------------------------------------------------------------- finding room and going there

/** Centres of 300 m squares of the land strip that hold no asset of the draft, tried in this order. */
const CANDIDATES: [number, number][] = [
  [0, 1000],
  [1500, 1000],
  [3000, 1000],
  [-1500, 1000],
  [4500, 1000],
]

/** A spot of the land (local metres) with no asset of the draft within 250 m, so shapes drawn there meet nobody. */
export async function freeSpot(api: Api): Promise<[number, number]> {
  const draft = (await api.draft())!
  const frame = await api.frame()
  const near = (x: number, y: number) =>
    draft.features.features.some((f) => {
      const flat = JSON.stringify(f.geometry.coordinates).match(/-?\d+\.\d+/g) ?? []
      for (let i = 0; i + 1 < flat.length; i += 2) {
        const [px, py] = frame.fromLonLat(Number(flat[i]), Number(flat[i + 1]))
        if (Math.hypot(px - x, py - y) < 250) return true
      }
      return false
    })
  const spot = CANDIDATES.find(([x, y]) => !near(x, y))
  if (!spot) throw new Error('no free land in the draft')
  return spot
}

/** Searches "lat, lon" of a local point, so the map is centred on it, and calibrates. */
export async function goToLocal(page: Page, api: Api, x: number, y: number): Promise<PlanMap> {
  const frame = await api.frame()
  const [lon, lat] = frame.toLonLat(x, y)
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(`${lat.toFixed(7)}, ${lon.toFixed(7)}`)
  await expect(page.getByRole('option', { name: /^Go to / })).toBeVisible()
  await search.press('Enter')
  await expect(page.getByTestId('map-pin')).toBeVisible()
  return new PlanMap(page).settle()
}

/** The ids of the assets the draft has now; with removeAllExcept the shared draft goes back to what it was. */
export async function snapshotIds(api: Api): Promise<Set<string>> {
  // The draft must exist before the snapshot: a draft the test opens later would start with every asset "new"
  const draft = await api.ensureDraft()
  return new Set(draft.features.features.map((f) => f.id))
}

/**
 * Registers the hooks that leave the shared draft as the test found it: the ids of its assets are taken before the test
 * (starting the draft first when there is none) and everything else is deleted afterwards. A test whose setup failed
 * deletes nothing.
 */
export function keepDraftClean(user: DemoUser = 'editor') {
  const state: { ids: ReadonlySet<string> | null } = { ids: null }
  test.beforeEach(async ({ request }) => {
    state.ids = null
    state.ids = await snapshotIds(new Api(request, user))
  })
  test.afterEach(async ({ request }) => {
    const ids = state.ids
    state.ids = null
    if (ids) await new Api(request, user).removeAllExcept(ids)
  })
  return state
}
