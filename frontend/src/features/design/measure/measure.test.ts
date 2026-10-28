import { toast } from 'sonner'
import { setActiveFrame, toLonLat, vincentyInverse } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { IN_3D_TOAST } from '../draw/draw-rules'
import { measure, measureClick, measureText, toggleMeasure } from './measure'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

beforeEach(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  useEditorStore.getState().reset()
  useUiStore.setState({ basemap: 'sentinel2', viewMode: 'split' })
  vi.mocked(toast).mockClear()
})

describe('measure', () => {
  it('measures a 470.4 m berth as 470.4 m, within 0.1 m of the geodesic value', () => {
    // Along the quay line (local x) and across it (local y): the plane and the ellipsoid agree in both directions
    for (const [dx, dy] of [
      [470.4, 0],
      [0, 470.4],
      [470.4 / Math.SQRT2, 470.4 / Math.SQRT2],
    ]) {
      const m = measure(toLonLat(-200, 0), toLonLat(-200 + dx, dy))
      expect(m.planarM).toBeCloseTo(470.4, 3)
      expect(Math.abs(m.geodesicM - m.planarM)).toBeLessThan(0.1)
    }
  })

  it('gives the true bearing of Vincenty', () => {
    const a = toLonLat(0, 0)
    const b = toLonLat(470.4, 0)
    expect(measure(a, b).bearingDeg).toBeCloseTo(vincentyInverse(a, b).bearing, 9)
    // local +x is turned -120.3 deg from east: the quay runs to 210.3 deg true
    expect(measure(a, b).bearingDeg).toBeCloseTo(210.3, 0)
  })

  it('writes "<d.dd> m (geodesic <g.gg> m), bearing <b.b> deg"', () => {
    expect(measureText({ planarM: 470.4, geodesicM: 470.377, bearingDeg: 87.26 })).toBe(
      '470.40 m (geodesic 470.38 m), bearing 87.3 deg',
    )
    // never 360.0
    expect(measureText({ planarM: 1, geodesicM: 1, bearingDeg: 359.96 })).toMatch(
      /bearing 0\.0 deg$/,
    )
  })
})

describe('the Measure tool', () => {
  const tool = () => useEditorStore.getState().tool

  it('toggles on and off', () => {
    toggleMeasure()
    expect(tool()?.kind).toBe('measure')
    toggleMeasure()
    expect(tool()).toBeNull()
    expect(toast).not.toHaveBeenCalled()
  })

  it('says where to measure in the 3D-only view', () => {
    useUiStore.setState({ viewMode: '3d' })
    toggleMeasure()
    expect(tool()?.kind).toBe('measure')
    expect(toast).toHaveBeenCalledWith(IN_3D_TOAST)
  })

  it('works over Google Maps: nothing is stored', () => {
    useUiStore.setState({ basemap: 'google' })
    toggleMeasure()
    expect(tool()?.kind).toBe('measure')
  })

  it('starts on the first click, ends and logs on the second, starts again on the third', () => {
    toggleMeasure()
    const a = toLonLat(0, 0)
    const b = toLonLat(470.4, 0)
    measureClick(a)
    expect(tool()?.points).toEqual([a])
    expect(useEditorStore.getState().historyLog).toHaveLength(0)

    measureClick(b)
    expect(tool()?.points).toEqual([a, b])
    const log = useEditorStore.getState().historyLog
    expect(log).toHaveLength(1)
    expect(log[0].label).toMatch(
      /^Measured 470\.40 m \(geodesic 470\.\d\d m\), bearing \d+\.\d deg$/,
    )
    // a measurement is not an edit: nothing to undo
    expect(useEditorStore.getState().undoStack).toHaveLength(0)

    measureClick(b)
    expect(tool()?.points).toEqual([b])
    expect(useEditorStore.getState().historyLog).toHaveLength(1)
  })

  it('ignores clicks when another tool is on', () => {
    useEditorStore.getState().setTool('draw', 'WAREHOUSE')
    measureClick(toLonLat(0, 0))
    expect(tool()?.points).toEqual([])
  })
})
