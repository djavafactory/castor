import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storedLonLat } from '@/features/design/edit/edit-model'
import { useEditPreview } from '@/features/design/edit/edit-preview-store'
import { assetTypeSchema } from '@/lib/api'
import { fromLonLat, rectangle, setActiveFrame, type Local } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { assetTypesFixture, layoutFeature, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { Map2D } from './map-2d'
import { useMapStore } from './map-store'
import { INITIAL_K, localToScreen } from './map-view'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const BASE = 'http://api.test/api/v1'
const SIZE = { width: 1000, height: 600 }
const NO_LIMITS = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }

const type = (code: string, geometryKind: 'RECT' | 'POLYGON') =>
  assetTypeSchema.parse({
    ...assetTypesFixture[0],
    code,
    label: code,
    category: 'STORAGE',
    geometryKind,
    flags: { ...assetTypesFixture[0]!.flags, mobile: false, solid: true },
    min: NO_LIMITS,
    max: NO_LIMITS,
  })

const SHED = type('SHED', 'RECT')
const YARD = type('YARD', 'POLYGON')

const lonLat = (p: Local) => [...storedLonLat(p)]
const ring = (points: Local[]) => [...points, points[0]!].map(lonLat)

const SHED_AT: Local = [200, 100]
const YARD_POINTS: Local[] = [
  [-300, 200],
  [-200, 200],
  [-200, 300],
  [-300, 300],
]

function draftWire() {
  const anchor = lonLat(SHED_AT)
  const shedRing = rectangle(storedLonLat(SHED_AT), 0, 50, 20).map((p) => [...p])
  const shed = layoutFeature(
    'shed-1',
    'SHED',
    {
      name: 'Shed 1',
      anchor,
      headingDeg: 0,
      lengthM: 50,
      widthM: 20,
      rowVersion: 3,
    },
    { type: 'Polygon', coordinates: [[...shedRing, shedRing[0]!]] },
  )
  const yard = layoutFeature(
    'yard-1',
    'YARD',
    { name: 'Yard 1', anchor: lonLat([-250, 250]), rowVersion: 5 },
    { type: 'Polygon', coordinates: [ring(YARD_POINTS)] },
  )
  return {
    layout: {
      id: 'draft-7',
      versionNo: 7,
      status: 'DRAFT',
      basedOnVersionNo: 1,
      createdBy: 'ada',
      createdAt: '2026-10-27T01:00:00Z',
      updatedBy: 'grace',
      updatedAt: '2026-10-27T02:00:00Z',
    },
    features: { type: 'FeatureCollection', features: [shed, yard] },
  }
}

interface Patched {
  id: string
  rowVersion: string | null
  body: Record<string, unknown>
}
let patched: Patched[] = []
/** When set, the server refuses the next PATCH with this response. */
let refusal: Response | null = null
let served = { draft: false, types: false }

function renderMap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () => {
      served.draft = true
      return HttpResponse.json(draftWire())
    }),
    http.get(`${BASE}/asset-types`, () => {
      served.types = true
      return HttpResponse.json([SHED, YARD])
    }),
    http.get(`${BASE}/ports/WESTPORTS/reference-features`, () =>
      HttpResponse.json({ type: 'FeatureCollection', features: [] }),
    ),
    http.post(`${BASE}/layouts/:id/validate`, () =>
      HttpResponse.json({ errors: 0, warnings: 0, issues: [] }),
    ),
    http.patch(`${BASE}/ports/WESTPORTS/layout/draft/assets/:id`, async ({ request, params }) => {
      if (refusal) return refusal
      const body = (await request.json()) as Record<string, unknown>
      patched.push({
        id: String(params.id),
        rowVersion: request.headers.get('If-Match'),
        body,
      })
      return HttpResponse.json({ id: params.id, typeCode: 'SHED', rowVersion: 4, ...body })
    }),
  )
  return render(
    <QueryClientProvider client={client}>
      <Map2D />
    </QueryClientProvider>,
  )
}

const canvas = () => screen.getByRole('img', { name: /Plan of the port/ })
const screenOf = (p: Local) => localToScreen(useMapStore.getState().view, SIZE, p[0], p[1])

function press(p: Local, init: Record<string, unknown> = {}) {
  const [x, y] = screenOf(p)
  fireEvent.pointerDown(canvas(), { clientX: x, clientY: y, button: 0, pointerId: 1, ...init })
}
function dragTo(p: Local, init: Record<string, unknown> = {}) {
  const [x, y] = screenOf(p)
  fireEvent.pointerMove(canvas(), { clientX: x, clientY: y, pointerId: 1, ...init })
}
function release(p: Local) {
  const [x, y] = screenOf(p)
  fireEvent.pointerUp(canvas(), { clientX: x, clientY: y, button: 0, pointerId: 1 })
}
function drag(from: Local, to: Local, init: Record<string, unknown> = {}) {
  press(from, init)
  dragTo(to, init)
  release(to)
}

async function ready(selected = 'shed-1') {
  renderMap()
  await waitFor(() => expect(useMapStore.getState().placed).toBe(true))
  await waitFor(() => expect(served).toEqual({ draft: true, types: true }))
  act(() => {
    useMapStore.getState().setSize(SIZE)
    useSelectionStore.getState().select(selected)
  })
  // The types and the draft are parsed one tick after they are served
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 30))
  })
}

const history = () => useEditorStore.getState().undoStack.map((e) => e.label)

beforeEach(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  HTMLElement.prototype.setPointerCapture = () => {}
  patched = []
  refusal = null
  served = { draft: false, types: false }
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
  useEditPreview.getState().setPreview(null)
  useUiStore.setState({
    mode: 'design',
    basemap: 'sentinel2',
    layerVisibility: {},
    isolatedLayer: null,
    lockedLayers: {},
    viewMode: 'split',
  })
})

describe('moving', () => {
  it('drags the selected shape, saves it once and puts one step on the undo stack', async () => {
    await ready()
    press(SHED_AT)
    dragTo([SHED_AT[0] + 40, SHED_AT[1] + 20])
    // while dragging, the preview carries the shape and the 3D ghost mirrors it
    expect(useEditPreview.getState().preview).toMatchObject({ assetId: 'shed-1', valid: true })
    release([SHED_AT[0] + 40, SHED_AT[1] + 20])

    await waitFor(() => expect(patched).toHaveLength(1))
    expect(patched[0]!.id).toBe('shed-1')
    const anchor = patched[0]!.body.anchor as [number, number]
    const [x, y] = fromLonLat(anchor[0], anchor[1])
    expect(x).toBeCloseTo(240, 1)
    expect(y).toBeCloseTo(120, 1)
    await waitFor(() => expect(history()).toEqual(['Moved Shed 1']))
    await waitFor(() => expect(useEditPreview.getState().preview).toBeNull())
  })

  it('moves only the selected shape: a press on an unselected one pans the map', async () => {
    await ready('yard-1')
    const before = useMapStore.getState().view
    drag(SHED_AT, [SHED_AT[0] + 40, SHED_AT[1]])
    expect(patched).toHaveLength(0)
    expect(useMapStore.getState().view.x).not.toBe(before.x)
  })

  it('a press that moves less than 3 px is a click, not a move', async () => {
    await ready()
    press(SHED_AT)
    dragTo([SHED_AT[0] + 2, SHED_AT[1]]) // 1 px at 0.5 px/m
    release([SHED_AT[0] + 2, SHED_AT[1]])
    expect(patched).toHaveLength(0)
    expect(useSelectionStore.getState().selectedAssetId).toBe('shed-1')
  })

  it('snaps back with "Not moved: <reason>" when a rule refuses the position', async () => {
    await ready()
    press(SHED_AT)
    dragTo([SHED_AT[0], -300])
    expect(useEditPreview.getState().preview).toMatchObject({
      valid: false,
      reason: 'Extends past the quay edge into the water',
    })
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Extends past the quay edge into the water',
    )
    release([SHED_AT[0], -300])

    expect(toast).toHaveBeenCalledWith('Not moved: Extends past the quay edge into the water')
    expect(patched).toHaveLength(0)
    expect(useEditPreview.getState().preview).toBeNull()
    expect(history()).toEqual([])
  })

  it('shows the reason of a refusal by the server as "Not moved: <reason>"', async () => {
    await ready()
    refusal = HttpResponse.json(
      {
        type: 'about:blank',
        title: 'Validation failed',
        status: 422,
        errors: [{ code: 'OVERLAP', message: 'Overlaps Shed 9' }],
      },
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
    )
    drag(SHED_AT, [SHED_AT[0] + 40, SHED_AT[1]])
    await waitFor(() => expect(toast).toHaveBeenCalledWith('Not moved: Overlaps Shed 9'))
    expect(history()).toEqual([])
    expect(useEditorStore.getState().failedSaves).toEqual([])
  })

  it('Escape drops the drag', async () => {
    await ready()
    press(SHED_AT)
    dragTo([SHED_AT[0] + 40, SHED_AT[1]])
    fireEvent.keyDown(screen.getByRole('region', { name: '2D map' }), { key: 'Escape' })
    expect(useEditPreview.getState().preview).toBeNull()
    release([SHED_AT[0] + 40, SHED_AT[1]])
    expect(patched).toHaveLength(0)
  })

  it('undo puts the old values back with one more PATCH', async () => {
    await ready()
    drag(SHED_AT, [SHED_AT[0] + 40, SHED_AT[1]])
    await waitFor(() => expect(history()).toEqual(['Moved Shed 1']))
    const entry = useEditorStore.getState().undo()!
    await entry.undo()
    expect(patched).toHaveLength(2)
    expect(patched[1]!.body.anchor).toEqual(lonLat(SHED_AT))
  })
})

describe('resizing and rotating', () => {
  it('a corner handle resizes in the asset own frame and the opposite corner stays', async () => {
    await ready()
    // the shape is 50 x 20 and upright in heading 0; find a corner by its handle
    const corners = rectangle(storedLonLat(SHED_AT), 0, 50, 20).map(([lon, lat]) =>
      fromLonLat(lon, lat),
    )
    const corner = corners[2]!
    const opposite = corners[0]!
    const along = [(corner[0] - opposite[0]) / 50, (corner[1] - opposite[1]) / 50] // unit vector, roughly the length axis
    const to: Local = [corner[0] + 10 * along[0]!, corner[1] + 10 * along[1]!]
    drag(corner, to)
    await waitFor(() => expect(patched).toHaveLength(1))
    expect(history()).toEqual(['Resized Shed 1'])
    const body = patched[0]!.body
    expect(body.lengthM as number).toBeGreaterThan(50)
    expect(body.headingDeg).toBe(0)
    expect(Number.isInteger((body.lengthM as number) * 100)).toBe(true)
  })

  it('the rotation knob sets a whole-degree heading, and Shift snaps to 15', async () => {
    await ready()
    const corners = rectangle(storedLonLat(SHED_AT), 0, 50, 20).map(([lon, lat]) =>
      fromLonLat(lon, lat),
    )
    // the knob is on the front edge's middle, 28 px (56 m at 0.5 px/m) beyond it
    const front: Local = [
      (corners[1]![0] + corners[2]![0]) / 2,
      (corners[1]![1] + corners[2]![1]) / 2,
    ]
    const out: Local = [front[0] - SHED_AT[0], front[1] - SHED_AT[1]]
    const norm = Math.hypot(out[0], out[1])
    const knob: Local = [front[0] + (out[0] / norm) * 56, front[1] + (out[1] / norm) * 56]
    // drag the knob to the point due east of the anchor: heading 90
    drag(knob, [SHED_AT[0] + 100, SHED_AT[1]], { shiftKey: true })
    await waitFor(() => expect(patched).toHaveLength(1))
    expect(history()).toEqual(['Rotated Shed 1'])
    const heading = patched[0]!.body.headingDeg as number
    expect(heading % 15).toBe(0)
  })
})

describe('vertices', () => {
  it('dragging a vertex handle moves that vertex', async () => {
    await ready('yard-1')
    drag(YARD_POINTS[2]!, [-150, 340])
    await waitFor(() => expect(patched).toHaveLength(1))
    expect(history()).toEqual(['Moved a vertex of Yard 1'])
    const coordinates = (patched[0]!.body.geometry as { coordinates: number[][][] }).coordinates[0]!
    expect(coordinates).toHaveLength(5)
    const [x, y] = fromLonLat(coordinates[2]![0]!, coordinates[2]![1]!)
    expect(x).toBeCloseTo(-150, 1)
    expect(y).toBeCloseTo(340, 1)
  })

  it('dragging an edge midpoint inserts a vertex', async () => {
    await ready('yard-1')
    const mid: Local = [-250, 200]
    drag(mid, [-250, 150])
    await waitFor(() => expect(patched).toHaveLength(1))
    expect(history()).toEqual(['Added a vertex to Yard 1'])
    const coordinates = (patched[0]!.body.geometry as { coordinates: number[][][] }).coordinates[0]!
    expect(coordinates).toHaveLength(6)
  })

  it('Alt+click on a vertex removes it while more than 3 remain', async () => {
    await ready('yard-1')
    press(YARD_POINTS[1]!, { altKey: true })
    release(YARD_POINTS[1]!)
    await waitFor(() => expect(patched).toHaveLength(1))
    expect(history()).toEqual(['Removed a vertex of Yard 1'])
    expect(
      (patched[0]!.body.geometry as { coordinates: number[][][] }).coordinates[0],
    ).toHaveLength(4)
  })

  it('refuses a vertex drag that makes the outline cross itself', async () => {
    await ready('yard-1')
    // pull the top right vertex across the left edge
    drag(YARD_POINTS[2]!, [-350, 250])
    expect(toast).toHaveBeenCalledWith('Not moved: the outline would cross itself')
    expect(patched).toHaveLength(0)
  })
})

describe('locked layers', () => {
  it('show no handles and no move: a drag pans the map', async () => {
    useUiStore.setState({ lockedLayers: { storage: true } })
    await ready()
    const before = useMapStore.getState().view
    drag(SHED_AT, [SHED_AT[0] + 40, SHED_AT[1]])
    expect(patched).toHaveLength(0)
    expect(useEditPreview.getState().preview).toBeNull()
    expect(useMapStore.getState().view.x).not.toBe(before.x)
  })

  it('and nothing is editable in Monitor mode', async () => {
    useUiStore.setState({ mode: 'monitor' })
    await ready()
    drag(SHED_AT, [SHED_AT[0] + 40, SHED_AT[1]])
    expect(patched).toHaveLength(0)
  })
})
