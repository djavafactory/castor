import { expect, test, type APIRequestContext, type Page, type Request } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): the real backend, its seeded Westports draft and the real rule check.
// Every edit is undone; when a test stops half way, afterEach writes the stored asset back through the API.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

const API = process.env.E2E_API_URL ?? 'http://localhost:8081/api'
const AUTH = `Basic ${Buffer.from('editor:editor').toString('base64')}`
const KEPT = ['anchor', 'headingDeg', 'lengthM', 'widthM', 'heightM', 'details'] as const

type Point = { x: number; y: number }
type Part = 'length' | 'width' | 'height' | 'rotate'
interface Hooks {
  grab(part: Part, amount?: number): Point | null
  body(dx: number, dy: number): { from: Point; to: Point } | null
  hit(x: number, y: number): Part | null
}
interface Stored {
  id: string
  geometry: unknown
  properties: Record<string, unknown> & { name: string; rowVersion: number }
}

async function draftOf(request: APIRequestContext) {
  const response = await request.get(`${API}/v1/ports/westports/layouts/draft`, {
    headers: { Authorization: AUTH },
  })
  expect(response.ok()).toBe(true)
  return (await response.json()) as {
    layout: { id: string }
    features: { features: Stored[] }
  }
}

async function storedAsset(request: APIRequestContext, name: string) {
  const draft = await draftOf(request)
  const asset = draft.features.features.find((f) => f.properties.name === name)
  if (!asset) throw new Error(`${name} is not in the draft`)
  return { layoutId: draft.layout.id, asset }
}

const comparable = (a: Stored) =>
  JSON.stringify([a.geometry, ...KEPT.map((k) => a.properties[k] ?? null)])

/** Writes the stored values back when the asset is not as it was. */
async function restore(request: APIRequestContext, before: Stored) {
  const { layoutId, asset: now } = await storedAsset(request, before.properties.name)
  if (comparable(now) === comparable(before)) return
  const body: Record<string, unknown> = { geometry: before.geometry }
  for (const k of KEPT) body[k] = before.properties[k] ?? null
  const response = await request.patch(`${API}/v1/layouts/${layoutId}/assets/${now.id}`, {
    headers: { Authorization: AUTH, 'If-Match': `"${now.properties.rowVersion}"` },
    data: body,
  })
  expect(response.ok(), await response.text()).toBe(true)
}

const isPatch = (r: Request) => r.method() === 'PATCH' && /\/assets\/[^/]+$/.test(r.url())

/** Split: the search (it selects and flies the 3D camera) lives on the 2D map. */
async function openDesign3D(page: Page, view: '3D' | 'Split' = 'Split') {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption('editor')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('group', { name: 'View' }).getByRole('button', { name: view }).click()
  await expect(page.getByText(/Draft v\d+/)).toBeVisible({ timeout: 20_000 })
}

/** Selects an asset through the search (which also flies the 3D camera to it) and waits for its gizmo. */
async function select(page: Page, name: string) {
  const search = page.getByRole('combobox', { name: 'Search places' })
  await search.fill(name)
  await expect(page.getByRole('option').first()).toContainText(name)
  await search.press('Enter')
  await expect.poll(() => grab(page, 'length'), { timeout: 15_000 }).not.toBeNull()
  // Let the camera finish its flight (600 ms) and settle: the same point five samples in a row
  const seen: string[] = []
  await expect
    .poll(
      async () => {
        seen.push(JSON.stringify(await grab(page, 'length')))
        return seen.length >= 5 && new Set(seen.slice(-5)).size === 1
      },
      { intervals: [200], timeout: 15_000 },
    )
    .toBe(true)
}

const grab = (page: Page, part: Part, amount = 0) =>
  page.evaluate(
    ([p, a]) =>
      (globalThis as unknown as { __castorGizmo?: Hooks }).__castorGizmo?.grab(
        p as Part,
        a as number,
      ) ?? null,
    [part, amount] as const,
  )

const hit = (page: Page, at: Point) =>
  page.evaluate(
    ([x, y]) =>
      (globalThis as unknown as { __castorGizmo?: Hooks }).__castorGizmo?.hit(x, y) ?? null,
    [at.x, at.y] as const,
  )

const body = (page: Page, dx: number, dy: number) =>
  page.evaluate(
    ([x, y]) =>
      (globalThis as unknown as { __castorGizmo?: Hooks }).__castorGizmo?.body(x, y) ?? null,
    [dx, dy] as const,
  )

async function drag(page: Page, from: Point, to: Point) {
  await page.mouse.move(from.x, from.y)
  await page.mouse.down()
  await page.mouse.move(to.x, to.y, { steps: 10 })
  await page.mouse.up()
}

/** Drags a part by `amount` along its axis and returns the body of the PATCH it sent. */
async function dragPart(page: Page, part: Part, amount: number) {
  const from = (await grab(page, part))!
  const to = (await grab(page, part, amount))!
  // The press lands on the part itself
  expect(await hit(page, from)).toBe(part)
  const saved = page.waitForRequest(isPatch)
  await drag(page, from, to)
  return (await saved).postDataJSON() as Record<string, unknown>
}

async function undo(page: Page) {
  const saved = page.waitForResponse((r) => isPatch(r.request()))
  await page.getByRole('button', { name: 'Undo' }).click()
  expect((await saved).ok()).toBe(true)
}

async function historyHas(page: Page, line: string) {
  const history = page.getByRole('button', { name: /^History \(\d+\)$/ })
  await history.click()
  await expect(page.getByText(line, { exact: true }).first()).toBeVisible()
  // Not Escape: that would also clear the selection
  await history.click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

const GATE = 'Gate house'
const PAD = 'Storage pad A1'

let original: Stored | null = null

test.afterEach(async ({ request }) => {
  if (original) await restore(request, original)
  original = null
})

test('length, width, height and rotation of a building: each one PATCH, one history line, undone', async ({
  page,
  request,
}) => {
  original = (await storedAsset(request, GATE)).asset
  await openDesign3D(page)
  await select(page, GATE)

  // Shorter and narrower: a bigger building could meet a neighbour, which the rules would refuse
  const length = await dragPart(page, 'length', -6)
  expect(length.lengthM as number).toBeGreaterThan(21)
  expect(length.lengthM as number).toBeLessThan(27)
  expect(length.widthM).toBe(12)
  expect(length.headingDeg).toBe(original.properties.headingDeg)
  await historyHas(page, `Changed the length of ${GATE}`)
  await undo(page)

  const width = await dragPart(page, 'width', -4)
  expect(width.widthM as number).toBeGreaterThan(6)
  expect(width.widthM as number).toBeLessThan(10)
  await historyHas(page, `Changed the width of ${GATE}`)
  await undo(page)

  const height = await dragPart(page, 'height', 3)
  expect(height.heightM as number).toBeGreaterThan(7)
  expect(height.heightM as number).toBeLessThan(11)
  expect(Object.keys(height)).toEqual(['heightM'])
  await historyHas(page, `Changed the height of ${GATE}`)
  await undo(page)

  const turned = await dragPart(page, 'rotate', 20)
  expect(Number.isInteger(turned.headingDeg)).toBe(true)
  expect(turned.headingDeg).not.toBe(original.properties.headingDeg)
  await historyHas(page, `Rotated ${GATE}`)
  await undo(page)

  // Every undo put the stored values back
  const { asset } = await storedAsset(request, GATE)
  expect(comparable(asset)).toBe(comparable(original))
})

test('pressing the body of an asset moves it on the ground; undo puts it back', async ({
  page,
  request,
}) => {
  original = (await storedAsset(request, GATE)).asset
  await openDesign3D(page)
  await select(page, GATE)

  const path = (await body(page, 3, 0))!
  expect(path).not.toBeNull()
  const saved = page.waitForRequest(isPatch)
  await drag(page, path.from, path.to)
  const moved = (await saved).postDataJSON() as Record<string, unknown>
  expect(moved.anchor).not.toEqual(original.properties.anchor)
  expect(moved.lengthM).toBe(30)
  await historyHas(page, `Moved ${GATE}`)
  await undo(page)
  expect(comparable((await storedAsset(request, GATE)).asset)).toBe(comparable(original))
})

test('an edit the rules refuse snaps back with "Not changed" and saves nothing', async ({
  page,
  request,
}) => {
  original = (await storedAsset(request, PAD)).asset
  await openDesign3D(page)
  await select(page, PAD)

  const patches: string[] = []
  page.on('request', (r) => {
    if (isPatch(r)) patches.push(r.url())
  })
  // 40 m wider runs into Storage pad B1, the next row
  const from = (await grab(page, 'width'))!
  expect(await hit(page, from)).toBe('width')
  await drag(page, from, (await grab(page, 'width', 40))!)
  await expect(page.getByText(/^Not changed: /)).toBeVisible()
  await page.waitForTimeout(500)
  expect(patches).toEqual([])
})

test('a storage pad dragged shorter in 3D changes its bay pitch, so its length stays bays x pitch', async ({
  page,
  request,
}) => {
  original = (await storedAsset(request, PAD)).asset
  await openDesign3D(page)
  await expect(page.getByRole('region', { name: '2D map' })).toBeVisible()
  await select(page, PAD)

  const changed = await dragPart(page, 'length', -10)
  const pad = (changed.details as { storagePad: Record<string, number> }).storagePad
  const bays = pad.bays
  expect(changed.lengthM as number).toBeCloseTo(bays * pad.bayPitchM, 1)
  expect(pad.bayPitchM).not.toBe(
    (original.properties.details as { storagePad: { bayPitchM: number } }).storagePad.bayPitchM,
  )
  await historyHas(page, `Changed the length of ${PAD}`)
  await undo(page)
  expect(comparable((await storedAsset(request, PAD)).asset)).toBe(comparable(original))
})

test('empty ground orbits the camera, and the gizmo is gone while a tool is active', async ({
  page,
  request,
}) => {
  original = (await storedAsset(request, GATE)).asset
  await openDesign3D(page)
  await select(page, GATE)
  const patches: string[] = []
  page.on('request', (r) => {
    if (isPatch(r)) patches.push(r.url())
  })

  const stage = (await page.getByTestId('port-scene').boundingBox())!
  const before = await grab(page, 'length')
  await drag(page, { x: stage.x + 40, y: stage.y + 40 }, { x: stage.x + 160, y: stage.y + 70 })
  // The camera turned: the gizmo is somewhere else on the screen, nothing was saved
  await expect
    .poll(async () => JSON.stringify(await grab(page, 'length')))
    .not.toBe(JSON.stringify(before))
  expect(patches).toEqual([])

  await page.getByTestId('asset-palette').getByRole('searchbox').fill('forklift')
  await page.getByTestId('palette-tile-FORKLIFT').click()
  await expect.poll(() => grab(page, 'length')).toBeNull()
  await page.keyboard.press('Escape')
  await select(page, GATE)
  await expect.poll(() => grab(page, 'length')).not.toBeNull()
})
