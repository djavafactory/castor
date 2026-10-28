import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { startDraw } from '@/features/design/draw/start-draw'
import { assetTypeSchema, type AssetType } from '@/lib/api'
import { setActiveFrame } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { assetTypesFixture, layoutWire, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { Map2D } from './map-2d'
import { useMapStore } from './map-store'
import { INITIAL_K, localToScreen } from './map-view'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const BASE = 'http://api.test/api/v1'
const SIZE = { width: 1000, height: 600 }
const BASE_TYPE = assetTypesFixture[0]!

const traced = (code: string, geometryKind: 'POLYGON' | 'LINE', label: string) =>
  assetTypeSchema.parse({
    ...BASE_TYPE,
    code,
    label,
    geometryKind,
    placementMode: 'DRAW',
    flags: { ...BASE_TYPE.flags, mobile: false },
    defaults: { ...BASE_TYPE.defaults, heightM: 9 },
    hint: `Click each corner of the ${label}.`,
  })

const WAREHOUSE = traced('WAREHOUSE_POLY', 'POLYGON', 'Warehouse (polygon)')
const FENCE = traced('FENCE', 'LINE', 'Fence')
const EXCAVATOR = assetTypeSchema.parse(BASE_TYPE)

let posted: unknown[] = []
/** When set, the server refuses the next asset with this problem. */
let refusal: Response | null = null

function renderMap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const draft = layoutWire({ versionId: 'draft-7', number: 7, status: 'DRAFT', assets: [] })
  draft.features.features = []
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () => HttpResponse.json(draft)),
    http.get(`${BASE}/asset-types`, () => HttpResponse.json([WAREHOUSE, FENCE, EXCAVATOR])),
    http.get(`${BASE}/ports/WESTPORTS/reference-features`, () =>
      HttpResponse.json({ type: 'FeatureCollection', features: [] }),
    ),
    http.post(`${BASE}/layouts/draft-7/assets`, async ({ request }) => {
      if (refusal) return refusal
      const body = (await request.json()) as Record<string, unknown>
      posted.push(body)
      const { type, ...properties } = body
      return HttpResponse.json(
        {
          feature: {
            type: 'Feature',
            id: 'new-1',
            geometry: { type: 'Polygon', coordinates: [] },
            properties: { ...properties, type, code: 'X-0001', rowVersion: 1 },
          },
          warnings: [],
        },
        { status: 201 },
      )
    }),
  )
  return render(
    <QueryClientProvider client={client}>
      <Map2D />
    </QueryClientProvider>,
  )
}

const canvas = () => screen.getByRole('img', { name: /Plan of the port/ })
const region = () => screen.getByRole('region', { name: '2D map' })

/** Click at a local point of the map. */
function clickAt(x: number, y: number) {
  const [px, py] = localToScreen(useMapStore.getState().view, SIZE, x, y)
  fireEvent.pointerDown(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
  fireEvent.pointerUp(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
}

async function ready(type: AssetType = WAREHOUSE) {
  renderMap()
  await waitFor(() => expect(useMapStore.getState().placed).toBe(true))
  act(() => useMapStore.getState().setSize(SIZE))
  // The tool starts the way the palette starts it; the map knows its shape once the asset types are in
  act(() => {
    startDraw(type)
  })
  await waitFor(() => expect(canvas()).toHaveClass('cursor-crosshair'))
}

beforeEach(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  HTMLElement.prototype.setPointerCapture = () => {}
  posted = []
  refusal = null
  vi.mocked(toast).mockClear()
  useMapStore.setState({
    view: { x: 0, y: 0, k: INITIAL_K },
    size: SIZE,
    placed: false,
    home: [0, 0],
    cursor: null,
    pin: null,
  })
  useSelectionStore.getState().clear()
  useEditorStore.getState().reset()
  useUiStore.setState({
    basemap: 'sentinel2',
    layerVisibility: {},
    isolatedLayer: null,
    viewMode: 'split',
  })
})

describe('starting the draw tool', () => {
  it('shows the type hint as a toast', async () => {
    await ready()
    expect(toast).toHaveBeenCalledWith('Click each corner of the Warehouse (polygon).')
    expect(useEditorStore.getState().tool?.kind).toBe('draw')
  })

  it('says where to draw when only the 3D view is showing', async () => {
    useUiStore.setState({ viewMode: '3d' })
    await ready()
    expect(toast).toHaveBeenCalledWith('Draw outlines and measure on the 2D map')
  })

  it('is refused on the Google basemap', () => {
    useUiStore.setState({ basemap: 'google' })
    expect(startDraw(WAREHOUSE)).toBe(false)
    expect(useEditorStore.getState().tool).toBeNull()
    expect(toast).not.toHaveBeenCalledWith('Click each corner of the Warehouse (polygon).')
  })
})

describe('drawing', () => {
  it('adds a vertex per click and ignores a click within 4 px of the last one', async () => {
    await ready()
    clickAt(0, 0)
    clickAt(0.5, 0.5) // a quarter of a pixel away at 0.5 px per metre
    clickAt(40, 0)
    expect(useEditorStore.getState().tool?.points).toHaveLength(2)
  })

  it('does not add a vertex when the pointer was dragged', async () => {
    await ready()
    fireEvent.pointerDown(canvas(), { clientX: 100, clientY: 100, button: 0, pointerId: 1 })
    fireEvent.pointerMove(canvas(), { clientX: 160, clientY: 100, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: 160, clientY: 100, button: 0, pointerId: 1 })
    expect(useEditorStore.getState().tool?.points).toHaveLength(0)
  })

  it('shows the readout and removes the last vertex with Undo point', async () => {
    await ready()
    clickAt(0, 0)
    clickAt(60, 0)
    clickAt(60, 60)
    const readout = await screen.findByRole('group', { name: 'Drawing: Warehouse (polygon)' })
    expect(readout).toHaveTextContent('Vertices3')
    expect(readout).toHaveTextContent(/Perimeter\d+\.\d m/)
    expect(readout).toHaveTextContent('Area1800 m2')
    fireEvent.click(screen.getByRole('button', { name: 'Undo point' }))
    expect(readout).toHaveTextContent('Vertices2')
  })

  it('warns in the readout while the outline crosses itself', async () => {
    await ready()
    clickAt(0, 0)
    clickAt(40, 40)
    clickAt(40, 0)
    clickAt(0, 40)
    expect(await screen.findByRole('alert')).toHaveTextContent('The outline crosses itself.')
  })

  it('cancels with the button', async () => {
    await ready()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel (Esc)' }))
    expect(useEditorStore.getState().tool).toBeNull()
    expect(screen.queryByTestId('draw-readout')).toBeNull()
  })
})

describe('finishing', () => {
  it('refuses too few corners', async () => {
    await ready()
    clickAt(0, 0)
    clickAt(40, 0)
    fireEvent.keyDown(region(), { key: 'Enter' })
    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith('An outline needs at least three corners'),
    )
    expect(posted).toHaveLength(0)
  })

  it('names the crossing edges and keeps the sketch', async () => {
    await ready()
    clickAt(0, 0)
    clickAt(40, 40)
    clickAt(40, 0)
    clickAt(0, 40)
    fireEvent.click(await screen.findByRole('button', { name: 'Finish (Enter)' }))
    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        'The outline crosses itself (edges 1 and 3). Move or remove points, then finish again.',
      ),
    )
    expect(posted).toHaveLength(0)
    expect(useEditorStore.getState().tool?.points).toHaveLength(4)
  })

  it('saves a six-vertex polygon, selects it and records the history', async () => {
    await ready()
    for (const [x, y] of [
      [0, 0],
      [80, -20],
      [160, 0],
      [160, 80],
      [80, 110],
      [0, 80],
    ] as const) {
      clickAt(x, y)
    }
    const points = useEditorStore.getState().tool!.points
    expect(points).toHaveLength(6)
    fireEvent.doubleClick(canvas())
    await waitFor(() => expect(posted).toHaveLength(1))

    const body = posted[0] as {
      type: string
      name: string
      geometry: { type: string; coordinates: number[][][] }
      provenance: Record<string, string>
      heightM: number
    }
    expect(body.type).toBe('WAREHOUSE_POLY')
    expect(body.name).toBe('Warehouse (polygon) 1')
    expect(body.heightM).toBe(9)
    expect(body.provenance).toEqual({
      source: 'SENTINEL2_EOX',
      accuracy: 'APPROXIMATE',
      note: 'Traced from 10 m satellite imagery: approximate, not a survey',
    })
    // The saved ring is the drawn vertices, identical in WGS84, then closed
    expect(body.geometry.coordinates[0]!.slice(0, -1)).toEqual(points)
    expect(body.geometry.coordinates[0]).toHaveLength(7)

    await waitFor(() => expect(useSelectionStore.getState().selectedAssetId).toBe('new-1'))
    expect(useEditorStore.getState().undoStack.at(-1)?.label).toBe('Drew Warehouse (polygon) 1')
    expect(useEditorStore.getState().tool).toBeNull()
  })

  it("records a line drawn on another basemap as the operator's own", async () => {
    useUiStore.setState({ basemap: 'none' })
    await ready(FENCE)
    clickAt(0, 0)
    clickAt(100, 0)
    fireEvent.keyDown(region(), { key: 'Enter' })
    await waitFor(() => expect(posted).toHaveLength(1))
    const body = posted[0] as { geometry: { type: string }; provenance: Record<string, string> }
    expect(body.geometry.type).toBe('LineString')
    expect(body.provenance).toMatchObject({
      source: 'OPERATOR',
      note: 'Drawn in the layout designer',
    })
  })

  it('says why the server refused the shape and keeps the sketch', async () => {
    refusal = HttpResponse.json(
      {
        title: 'Layout validation failed',
        status: 422,
        errors: [{ code: 'X', message: 'overlaps the quay' }],
      },
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
    )
    await ready(FENCE)
    clickAt(0, 0)
    clickAt(100, 0)
    fireEvent.keyDown(region(), { key: 'Enter' })
    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith('Cannot save this shape: overlaps the quay'),
    )
    expect(useEditorStore.getState().tool?.points).toHaveLength(2)
  })
})

describe('point types', () => {
  it('are placed with one click of the draw tool', async () => {
    await ready(EXCAVATOR)
    clickAt(10, 10)
    await waitFor(() => expect(posted).toHaveLength(1))
    const body = posted[0] as { type: string; geometry: { type: string }; lengthM: number }
    expect(body.type).toBe('EXCAVATOR')
    expect(body.geometry.type).toBe('Polygon')
    expect(body.lengthM).toBe(10)
  })
})
