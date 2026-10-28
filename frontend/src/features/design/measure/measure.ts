import { toast } from 'sonner'
import { fromLonLat, vincentyInverse, type LonLat } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { IN_3D_TOAST } from '../draw/draw-rules'

export const MEASURE_TIP = 'Measure a distance (M)'

export interface Measurement {
  /** Distance on the local plane (the measuring frame), metres. */
  planarM: number
  /** WGS84 Vincenty distance, metres: the check of the plane distance. */
  geodesicM: number
  /** True bearing from the first point, degrees clockwise from north. */
  bearingDeg: number
}

export function measure(a: LonLat, b: LonLat): Measurement {
  const [ax, ay] = fromLonLat(a[0], a[1])
  const [bx, by] = fromLonLat(b[0], b[1])
  const { distance, bearing } = vincentyInverse(a, b)
  return { planarM: Math.hypot(bx - ax, by - ay), geodesicM: distance, bearingDeg: bearing }
}

/** "<d.dd> m (geodesic <g.gg> m), bearing <b.b> deg" */
export function measureText(m: Measurement): string {
  // 359.96 deg would print as 360.0
  const bearing = Number(m.bearingDeg.toFixed(1)) % 360
  return `${m.planarM.toFixed(2)} m (geodesic ${m.geodesicM.toFixed(2)} m), bearing ${bearing.toFixed(1)} deg`
}

/** M and the strip button: turns the tool on, or off when it is on. Measuring happens on the 2D map. */
export function toggleMeasure(): void {
  const editor = useEditorStore.getState()
  if (editor.tool?.kind === 'measure') {
    editor.clearTool()
    return
  }
  editor.setTool('measure')
  if (useUiStore.getState().viewMode === '3d') toast(IN_3D_TOAST)
}

/** A click of the Measure tool: the first starts a line, the second ends it (and logs it), the third starts again. */
export function measureClick(at: LonLat): void {
  const editor = useEditorStore.getState()
  const tool = editor.tool
  if (tool?.kind !== 'measure') return
  if (tool.points.length >= 2) editor.resetPoints()
  editor.addPoint(at)
  const points = useEditorStore.getState().tool?.points ?? []
  if (points.length === 2) {
    editor.logHistory(`Measured ${measureText(measure(points[0], points[1]))}`)
  }
}
