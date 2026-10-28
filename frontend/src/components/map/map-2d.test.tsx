import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { GOOGLE_NOTE } from '@/lib/basemap'
import { fromLonLat, setActiveFrame, toLonLat } from '@/lib/geo'
import { toggleMeasure } from '@/features/design/measure/measure'
import { palette } from '@/features/scene/palette'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutWire, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { CursorReadout } from './cursor-readout'
import { Map2D } from './map-2d'
import { conventionalTerminalCentre } from './map-scene'
import { PIN_MS, useMapStore } from './map-store'
import { INITIAL_K, localToScreen, wheelFactor } from './map-view'

const BASE = 'http://api.test/api/v1'
const SIZE = { width: 1000, height: 600 }

/** The main gate control point of the port data. */
const MAIN_GATE = { lon: 101.3093602, lat: 2.9518844 }

function polygon(x: number, y: number, w: number, h: number) {
  // Local metres to WGS84 with the frame of the test port: built through lib/geo once it is active
  const corners = [
    [x, y],
    [x + w, y],
    [x + w, y + h],
    [x, y + h],
    [x, y],
  ]
  return { type: 'Polygon', coordinates: [corners.map(([cx, cy]) => toLonLat(cx, cy))] }
}

/** Features of the draft with real shapes: a terminal boundary, a pad and a berth, all in local metres. */
function draftWire() {
  const wire = layoutWire({ versionId: 'draft-7', number: 7, status: 'DRAFT', assets: [] })
  wire.features.features = [
    {
      type: 'Feature',
      id: 't1',
      geometry: polygon(-600, -20, 1200, 700),
      properties: {
        type: 'TERMINAL_BOUNDARY',
        name: 'Conventional and Bulk Terminal',
        rowVersion: 1,
      },
    },
    {
      type: 'Feature',
      id: 'pad-1',
      geometry: polygon(100, 100, 80, 60),
      properties: { type: 'STORAGE_PAD', name: 'Pad 1', rowVersion: 1, lengthM: 80, widthM: 60 },
    },
    {
      type: 'Feature',
      id: 'berth-2',
      geometry: {
        type: 'LineString',
        coordinates: [toLonLat(-200, 0), toLonLat(200, 0)],
      },
      properties: { type: 'BERTH', name: 'Berth 2', rowVersion: 1, lengthM: 400 },
    },
  ] as never
  return wire
}

function renderMap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () => HttpResponse.json(draftWire())),
    http.get(`${BASE}/asset-types`, () => HttpResponse.json([])),
    http.get(`${BASE}/ports/WESTPORTS/reference-features`, () =>
      HttpResponse.json({ type: 'FeatureCollection', features: [] }),
    ),
  )
  return render(
    <QueryClientProvider client={client}>
      <Map2D />
      <CursorReadout />
    </QueryClientProvider>,
  )
}

const canvas = () => screen.getByRole('img', { name: /Plan of the port/ })

async function loaded() {
  const view = renderMap()
  await waitFor(() => expect(useMapStore.getState().placed).toBe(true))
  // jsdom has no layout; the map's box is what the test says it is
  act(() => useMapStore.getState().setSize(SIZE))
  return view
}

beforeEach(() => {
  // The shapes of this file are built in local metres, so the frame is on before the port record arrives
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  HTMLElement.prototype.setPointerCapture = () => {}
  useMapStore.setState({
    view: { x: 0, y: 0, k: INITIAL_K },
    size: SIZE,
    placed: false,
    home: [0, 0],
    cursor: null,
    pin: null,
  })
  useSelectionStore.getState().clear()
  useUiStore.setState({ basemap: 'sentinel2', layerVisibility: {}, isolatedLayer: null })
})

describe('2D map', () => {
  it('shows the plan with a scale bar, a north arrow and the search box', async () => {
    await loaded()
    expect(screen.getByRole('region', { name: '2D map' })).toBeInTheDocument()
    expect(canvas()).toBeInTheDocument()
    expect(screen.getByTestId('scale-bar')).toHaveTextContent('200 m')
    expect(screen.getByRole('combobox', { name: 'Search places' })).toBeInTheDocument()
  })

  it('turns the north needle by the rotation of the quay frame', async () => {
    await loaded()
    expect(screen.getByTestId('north-needle')).toHaveAttribute('transform', 'rotate(-120.299)')
  })

  it('starts centred on the conventional terminal at 0.5 px per metre', async () => {
    await loaded()
    const { view } = useMapStore.getState()
    expect(view.k).toBe(0.5)
    expect(view.x).toBeCloseTo(0, 0)
    expect(view.y).toBeCloseTo(330, 0)
  })

  it('names the conventional terminal first, then any terminal, else nothing', () => {
    const shape = (id: string, name: string, x0: number) =>
      ({
        id,
        typeCode: 'TERMINAL_BOUNDARY',
        name,
        box: { x0, x1: x0 + 100, y0: 0, y1: 50 },
      }) as never
    expect(
      conventionalTerminalCentre([
        shape('a', 'Container terminals', 500),
        shape('b', 'Conventional', 0),
      ]),
    ).toEqual([50, 25])
    expect(conventionalTerminalCentre([shape('a', 'Container terminals', 500)])).toEqual([550, 25])
    expect(conventionalTerminalCentre([])).toBeNull()
  })
})

describe('pan', () => {
  it('moves with the arrow keys', async () => {
    await loaded()
    const region = screen.getByRole('region', { name: '2D map' })
    const before = useMapStore.getState().view

    fireEvent.keyDown(region, { key: 'ArrowRight' })
    fireEvent.keyDown(region, { key: 'ArrowUp' })

    const after = useMapStore.getState().view
    // 80 px at 0.5 px per metre is 160 m
    expect(after.x - before.x).toBeCloseTo(160, 6)
    expect(after.y - before.y).toBeCloseTo(160, 6)
    fireEvent.keyDown(region, { key: 'ArrowLeft' })
    fireEvent.keyDown(region, { key: 'ArrowDown' })
    expect(useMapStore.getState().view.x).toBeCloseTo(before.x, 6)
    expect(useMapStore.getState().view.y).toBeCloseTo(before.y, 6)
  })

  it('moves the ground with a drag on empty space', async () => {
    await loaded()
    const before = useMapStore.getState().view
    fireEvent.pointerDown(canvas(), { clientX: 100, clientY: 100, button: 0, pointerId: 1 })
    fireEvent.pointerMove(canvas(), { clientX: 160, clientY: 130, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: 160, clientY: 130, button: 0, pointerId: 1 })

    const after = useMapStore.getState().view
    expect(after.x).toBeCloseTo(before.x - 60 / before.k, 6)
    expect(after.y).toBeCloseTo(before.y + 30 / before.k, 6)
  })

  it('also drags with the right and the middle button', async () => {
    await loaded()
    for (const button of [1, 2]) {
      const before = useMapStore.getState().view
      fireEvent.pointerDown(canvas(), { clientX: 300, clientY: 300, button, pointerId: 2 })
      fireEvent.pointerMove(canvas(), { clientX: 340, clientY: 300, pointerId: 2 })
      fireEvent.pointerUp(canvas(), { clientX: 340, clientY: 300, button, pointerId: 2 })
      expect(useMapStore.getState().view.x).toBeCloseTo(before.x - 40 / before.k, 6)
    }
  })

  it('shows no browser menu on the right button', async () => {
    await loaded()
    expect(fireEvent.contextMenu(canvas())).toBe(false)
  })
})

describe('zoom', () => {
  it('zooms with the wheel by exp(-deltaY x 0.0015) around the pointer', async () => {
    await loaded()
    const before = useMapStore.getState().view
    const [x, y] = [before.x + (800 - 500) / before.k, before.y - (200 - 300) / before.k]

    fireEvent.wheel(canvas(), { deltaY: -200, clientX: 800, clientY: 200 })

    const after = useMapStore.getState().view
    expect(after.k).toBeCloseTo(before.k * wheelFactor(-200), 9)
    // the point under the pointer stays under it
    const [px, py] = localToScreen(after, SIZE, x, y)
    expect(px).toBeCloseTo(800, 6)
    expect(py).toBeCloseTo(200, 6)
  })

  it('stays inside 0.02 to 20 px per metre', async () => {
    await loaded()
    for (let i = 0; i < 40; i++) fireEvent.wheel(canvas(), { deltaY: -1000 })
    expect(useMapStore.getState().view.k).toBe(20)
    for (let i = 0; i < 80; i++) fireEvent.wheel(canvas(), { deltaY: 1000 })
    expect(useMapStore.getState().view.k).toBe(0.02)
  })

  it('zooms with + and - on the keyboard', async () => {
    await loaded()
    const region = screen.getByRole('region', { name: '2D map' })
    fireEvent.keyDown(region, { key: '+' })
    expect(useMapStore.getState().view.k).toBeCloseTo(0.5 * 1.35, 9)
    fireEvent.keyDown(region, { key: '-' })
    expect(useMapStore.getState().view.k).toBeCloseTo(0.5, 9)
  })

  it('changes the scale bar with the zoom', async () => {
    await loaded()
    act(() => useMapStore.setState({ view: { x: 0, y: 0, k: 0.05 } }))
    expect(screen.getByTestId('scale-bar')).toHaveTextContent('2 km')
  })
})

describe('cursor readout', () => {
  it('shows nothing until the pointer is over the map', async () => {
    await loaded()
    expect(screen.getByTestId('cursor-readout')).toHaveTextContent('Cursor: -')
  })

  it('shows latitude, longitude and local metres of the pointer', async () => {
    await loaded()
    const [px, py] = localToScreen(useMapStore.getState().view, SIZE, 100, 200)
    fireEvent.pointerMove(canvas(), { clientX: px, clientY: py })

    const [lon, lat] = toLonLat(100, 200)
    // two spaces before the metres, as the ticket has it (toHaveTextContent would collapse them)
    expect(screen.getByTestId('cursor-readout').textContent).toBe(
      `${lat.toFixed(7)}, ${lon.toFixed(7)}  (x 100.0, y 200.0 m)`,
    )
    expect(screen.getByTestId('cursor-readout')).toHaveAttribute(
      'title',
      'Cursor position: WGS84 latitude, longitude and local metres',
    )
  })

  it('has the coordinates of the main gate control point within 0.1 m', async () => {
    await loaded()
    // zoomed in to 20 px per metre, where one pixel is 0.05 m, and put on the gate
    const [gx, gy] = fromLonLat(MAIN_GATE.lon, MAIN_GATE.lat)
    act(() => useMapStore.setState({ view: { x: gx + 3, y: gy - 2, k: 20 } }))
    const [px, py] = localToScreen(useMapStore.getState().view, SIZE, gx, gy)
    fireEvent.pointerMove(canvas(), { clientX: Math.round(px), clientY: Math.round(py) })

    const cursor = useMapStore.getState().cursor
    expect(cursor).not.toBeNull()
    const [cx, cy] = fromLonLat(cursor!.lon, cursor!.lat)
    expect(Math.hypot(cx - gx, cy - gy)).toBeLessThan(0.1)
    expect(cursor!.x).toBeCloseTo(gx, 1)
    expect(cursor!.y).toBeCloseTo(gy, 1)
  })

  it('clears when the pointer leaves the map', async () => {
    await loaded()
    fireEvent.pointerMove(canvas(), { clientX: 400, clientY: 300 })
    expect(useMapStore.getState().cursor).not.toBeNull()
    fireEvent.pointerLeave(canvas())
    expect(useMapStore.getState().cursor).toBeNull()
  })
})

describe('selection', () => {
  async function click(local: [number, number]) {
    const [px, py] = localToScreen(useMapStore.getState().view, SIZE, local[0], local[1])
    fireEvent.pointerDown(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
  }

  it('selects the shape that is clicked and clears on empty space', async () => {
    await loaded()
    await click([140, 130])
    expect(useSelectionStore.getState().selectedAssetId).toBe('pad-1')

    await click([300, 400])
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('picks a berth by its line', async () => {
    await loaded()
    await click([0, 1])
    expect(useSelectionStore.getState().selectedAssetId).toBe('berth-2')
  })

  it('does not select anything when the click was a drag', async () => {
    await loaded()
    const [px, py] = localToScreen(useMapStore.getState().view, SIZE, 140, 130)
    fireEvent.pointerDown(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
    fireEvent.pointerMove(canvas(), { clientX: px + 30, clientY: py, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: px + 30, clientY: py, button: 0, pointerId: 1 })
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('cannot hit an asset of a hidden layer', async () => {
    await loaded()
    act(() => useUiStore.setState({ layerVisibility: { 'storage-pads': false } }))
    await click([140, 130])
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })
})

describe('shared selection', () => {
  it('outlines an asset selected in 3D on the next animation frame', async () => {
    // A canvas that writes down the colour of every stroke, and frames that run only when the test says so
    const strokes: unknown[] = []
    const ctx = new Proxy(
      {},
      {
        get: (target: Record<string | symbol, unknown>, key) =>
          key in target
            ? target[key]
            : key === 'stroke'
              ? () => strokes.push(target.strokeStyle)
              : key === 'measureText'
                ? () => ({ width: 0 })
                : () => undefined,
        set: (target, key, value) => {
          target[key] = value
          return true
        },
      },
    )
    const frames: FrameRequestCallback[] = []
    const getContext = HTMLCanvasElement.prototype.getContext
    const path2d = (globalThis as { Path2D?: unknown }).Path2D
    ;(globalThis as { Path2D?: unknown }).Path2D = class {
      moveTo() {}
      lineTo() {}
      closePath() {}
    }
    HTMLCanvasElement.prototype.getContext = (() => ctx) as never
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      frames.push(cb)
      return frames.length
    })
    try {
      await loaded()
      frames.splice(0).forEach((f) => f(0))
      strokes.length = 0

      act(() => useSelectionStore.getState().select('pad-1'))
      expect(frames).toHaveLength(1)
      frames.splice(0).forEach((f) => f(0))
      expect(strokes).toContain(palette.signal)
    } finally {
      raf.mockRestore()
      HTMLCanvasElement.prototype.getContext = getContext
      ;(globalThis as { Path2D?: unknown }).Path2D = path2d
    }
  })
})

describe('measure tool', () => {
  async function click(local: [number, number]) {
    const [px, py] = localToScreen(useMapStore.getState().view, SIZE, local[0], local[1])
    fireEvent.pointerDown(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: px, clientY: py, button: 0, pointerId: 1 })
  }

  beforeEach(() => useEditorStore.getState().reset())

  it('measures between two clicks without selecting, and logs the measurement', async () => {
    await loaded()
    act(() => toggleMeasure())
    expect(canvas()).toHaveClass('cursor-crosshair')
    // the ends of Berth 2, 400 m apart
    await click([-200, 0])
    await click([200, 0])
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
    const points = useEditorStore.getState().tool?.points ?? []
    expect(points).toHaveLength(2)
    const [x0, y0] = fromLonLat(...points[0])
    const [x1, y1] = fromLonLat(...points[1])
    expect(Math.hypot(x1 - x0, y1 - y0)).toBeCloseTo(400, 0)
    expect(useEditorStore.getState().historyLog[0].label).toMatch(/^Measured 40\d\.\d\d m/)
  })

  it('still pans on a drag', async () => {
    await loaded()
    act(() => toggleMeasure())
    const before = useMapStore.getState().view
    fireEvent.pointerDown(canvas(), { clientX: 300, clientY: 300, button: 0, pointerId: 1 })
    fireEvent.pointerMove(canvas(), { clientX: 360, clientY: 300, pointerId: 1 })
    fireEvent.pointerUp(canvas(), { clientX: 360, clientY: 300, button: 0, pointerId: 1 })
    expect(useMapStore.getState().view.x).toBeCloseTo(before.x - 60 / before.k, 6)
    expect(useEditorStore.getState().tool?.points).toEqual([])
  })
})

describe('search pin', () => {
  it('is shown where the search dropped it and gone after four seconds', async () => {
    await loaded()
    act(() => useMapStore.getState().dropPin([0, 330]))
    const pin = screen.getByTestId('map-pin')
    expect(pin).toHaveStyle({ left: '500px', top: '300px' })

    await waitFor(() => expect(screen.queryByTestId('map-pin')).not.toBeInTheDocument(), {
      timeout: PIN_MS + 1500,
    })
  }, 10_000)

  it('is replaced by a newer pin without the older timer removing it', async () => {
    await loaded()
    act(() => useMapStore.getState().dropPin([0, 0]))
    const first = useMapStore.getState().pin!
    act(() => useMapStore.getState().dropPin([10, 10]))
    act(() => useMapStore.getState().clearPin(first.id))
    expect(useMapStore.getState().pin).toMatchObject({ x: 10, y: 10 })
  })
})

describe('basemap on the map', () => {
  it('says Google Maps is a locator only, and only on Google', async () => {
    await loaded()
    expect(screen.queryByText(GOOGLE_NOTE)).not.toBeInTheDocument()
    act(() => useUiStore.setState({ basemap: 'google' }))
    expect(screen.getByText(GOOGLE_NOTE)).toBeInTheDocument()
    act(() => useUiStore.setState({ basemap: 'none' }))
    expect(screen.queryByText(GOOGLE_NOTE)).not.toBeInTheDocument()
  })
})
