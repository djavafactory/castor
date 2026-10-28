import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMapStore } from '@/components/map/map-store'
import { INITIAL_K } from '@/components/map/map-view'
import { useCameraStore } from '@/components/scene/stage/view-state'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutWire, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { ToolStrip } from './tool-strip'
import { WorkArea } from './work-area'
import { showsMap, showsScene } from './view-modes'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

const BASE = 'http://api.test/api/v1'

function renderWith(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () =>
      HttpResponse.json(layoutWire({ versionId: 'd', number: 2, status: 'DRAFT', assets: [] })),
    ),
    http.get(`${BASE}/ports/WESTPORTS/layouts/current`, () =>
      HttpResponse.json(layoutWire({ versionId: 'p', number: 1, status: 'PUBLISHED', assets: [] })),
    ),
    http.get(`${BASE}/asset-types`, () => HttpResponse.json([])),
    http.get(`${BASE}/ports/WESTPORTS/reference-features`, () =>
      HttpResponse.json({ type: 'FeatureCollection', features: [] }),
    ),
    http.get(`${BASE}/version`, () =>
      HttpResponse.json({ version: '0', commit: 'x', buildTime: '', environment: 'test' }),
    ),
  )
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

beforeEach(() => {
  useCameraStore.setState({ autoRotate: false, command: null })
  useMapStore.setState({
    view: { x: 10, y: 20, k: INITIAL_K },
    size: { width: 1000, height: 600 },
    home: [10, 20],
    placed: true,
    cursor: null,
    pin: null,
  })
  useUiStore.setState({
    mode: 'design',
    viewMode: 'split',
    panelHidden: true,
    paletteHidden: true,
    basemap: 'sentinel2',
    layerVisibility: {},
    isolatedLayer: null,
    openMenu: null,
  })
})

describe('which views are on screen', () => {
  it('Monitor is the 3D replica only; Design shows the 2D map in 2D and Split', () => {
    expect([showsMap('monitor', 'split'), showsScene('monitor', '2d')]).toEqual([false, true])
    expect([showsMap('design', '2d'), showsScene('design', '2d')]).toEqual([true, false])
    expect([showsMap('design', '3d'), showsScene('design', '3d')]).toEqual([false, true])
    expect([showsMap('design', 'split'), showsScene('design', 'split')]).toEqual([true, true])
  })
})

describe('work area', () => {
  it('shows the 2D map beside the 3D stage in Split', async () => {
    renderWith(<WorkArea />)
    expect(await screen.findByRole('region', { name: '2D map' })).toBeInTheDocument()
    // jsdom has no WebGL, so the 3D side is the stage's own notice
    expect(screen.getByText(/3D view not available/)).toBeInTheDocument()
  })

  it('shows only the 2D map in 2D, and none of it in 3D or Monitor', async () => {
    useUiStore.setState({ viewMode: '2d' })
    const { unmount } = renderWith(<WorkArea />)
    expect(await screen.findByRole('region', { name: '2D map' })).toBeInTheDocument()
    unmount()

    useUiStore.setState({ viewMode: '3d' })
    const second = renderWith(<WorkArea />)
    expect(screen.queryByRole('region', { name: '2D map' })).not.toBeInTheDocument()
    second.unmount()

    useUiStore.setState({ mode: 'monitor', viewMode: 'split' })
    renderWith(<WorkArea />)
    expect(screen.queryByRole('region', { name: '2D map' })).not.toBeInTheDocument()
  })

  it('has the cursor readout and the basemap select in the bottom strip of Design', async () => {
    renderWith(<WorkArea />)
    const strip = await screen.findByRole('contentinfo', { name: 'Status' })
    expect(strip).toContainElement(screen.getByTestId('cursor-readout'))
    expect(strip).toContainElement(screen.getByRole('combobox', { name: 'Basemap' }))
  })

  it('has no bottom strip, and so no basemap select, in Monitor', async () => {
    useUiStore.setState({ mode: 'monitor' })
    renderWith(<WorkArea />)
    expect(screen.queryByRole('combobox', { name: 'Basemap' })).not.toBeInTheDocument()
  })
})

describe('tool strip buttons move the views that are shown', () => {
  it('Zoom in and out zoom the 2D map by 1.35 and leave the 3D camera alone in 2D', async () => {
    useUiStore.setState({ viewMode: '2d' })
    renderWith(<ToolStrip />)

    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(useMapStore.getState().view.k).toBeCloseTo(INITIAL_K * 1.35, 9)
    await userEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
    expect(useMapStore.getState().view.k).toBeCloseTo(INITIAL_K, 9)
    expect(useCameraStore.getState().command).toBeNull()
  })

  it('zoom both views in Split, and only the camera in 3D', async () => {
    renderWith(<ToolStrip />)
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(useMapStore.getState().view.k).toBeGreaterThan(INITIAL_K)
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom' })

    useMapStore.setState({ view: { x: 10, y: 20, k: INITIAL_K } })
    useCameraStore.setState({ command: null })
    useUiStore.setState({ viewMode: '3d' })
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(useMapStore.getState().view.k).toBe(INITIAL_K)
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom' })
  })

  it('Monitor zooms the camera only', async () => {
    useUiStore.setState({ mode: 'monitor' })
    renderWith(<ToolStrip />)
    await userEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
    expect(useMapStore.getState().view.k).toBe(INITIAL_K)
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom' })
  })

  it('Reset puts the 2D map back on the conventional terminal at 0.5', async () => {
    useUiStore.setState({ viewMode: '2d' })
    useMapStore.setState({ view: { x: 900, y: -400, k: 7 } })
    renderWith(<ToolStrip />)
    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(useMapStore.getState().view).toEqual({ x: 10, y: 20, k: INITIAL_K })
  })

  it('Reset in Split puts the 3D camera back on Overview and the 2D map on its first view', async () => {
    useMapStore.setState({ view: { x: 900, y: -400, k: 7 } })
    useCameraStore.setState({ autoRotate: true })
    renderWith(<ToolStrip />)
    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(useMapStore.getState().view).toEqual({ x: 10, y: 20, k: INITIAL_K })
    expect(useCameraStore.getState()).toMatchObject({
      autoRotate: false,
      command: { type: 'preset', key: 'overview' },
    })
  })
})
