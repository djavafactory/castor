import {
  fromLonLat,
  lineLength,
  polygonArea,
  polygonPerimeter,
  selfIntersection,
  toLonLat,
  type Local,
  type LonLat,
} from '@/lib/geo'
import type { AssetType, LayoutAsset } from '@/lib/api'
import type { Basemap } from '@/lib/basemap'

/** A click that moves less than this between press and release adds a vertex; one this near the last vertex is ignored. */
export const VERTEX_SLOP_PX = 4
export const MIN_AREA_M2 = 4

export const TOO_FEW_POLYGON = 'An outline needs at least three corners'
export const TOO_FEW_LINE = 'A line needs at least two points'
export const TOO_SMALL = `The outline is smaller than ${MIN_AREA_M2} m2`
export const CROSSES_READOUT = 'The outline crosses itself.'
export const IN_3D_TOAST = 'Draw outlines and measure on the 2D map'

export type DrawShape = 'polygon' | 'line'

export function shapeOf(type: Pick<AssetType, 'geometryKind'>): DrawShape | 'point' | null {
  switch (type.geometryKind) {
    case 'POLYGON':
      return 'polygon'
    case 'LINE':
      return 'line'
    case 'POINT':
      return 'point'
    default:
      return null
  }
}

/** Seven decimals is about a centimetre; what is drawn, shown and stored are the same numbers. */
export function roundLonLat([lon, lat]: LonLat): LonLat {
  return [Math.round(lon * 1e7) / 1e7, Math.round(lat * 1e7) / 1e7]
}

export const formatCoordinate = ([lon, lat]: LonLat) => `${lat.toFixed(7)}, ${lon.toFixed(7)}`

export interface SketchStats {
  vertices: number
  local: Local[]
  /** Perimeter of a polygon, length of a line, in local metres (the measuring frame, EPSG:3380 equivalent). */
  size: number
  /** Square metres; polygons only. */
  area: number | null
  /** The first pair of edges that cross, polygons only. */
  crossing: [number, number] | null
}

export function sketchStats(shape: DrawShape, points: readonly LonLat[]): SketchStats {
  const local = points.map(([lon, lat]) => fromLonLat(lon, lat))
  if (shape === 'line') {
    return { vertices: local.length, local, size: lineLength(local), area: null, crossing: null }
  }
  return {
    vertices: local.length,
    local,
    size: polygonPerimeter(local),
    area: local.length >= 3 ? polygonArea(local) : null,
    crossing: selfIntersection(local),
  }
}

/** Why the sketch cannot be finished yet, in the order the ticket fixes; null when it can. */
export function finishProblem(shape: DrawShape, stats: SketchStats): string | null {
  if (shape === 'line') return stats.vertices < 2 ? TOO_FEW_LINE : null
  if (stats.vertices < 3) return TOO_FEW_POLYGON
  if (stats.crossing) {
    const [i, j] = stats.crossing
    return `The outline crosses itself (edges ${i + 1} and ${j + 1}). Move or remove points, then finish again.`
  }
  if ((stats.area ?? 0) < MIN_AREA_M2) return TOO_SMALL
  return null
}

/** The GeoJSON geometry of a finished sketch: a closed ring for a polygon, the vertices as drawn for a line. */
export function sketchGeometry(shape: DrawShape, points: readonly LonLat[]) {
  return shape === 'line'
    ? { type: 'LineString' as const, coordinates: points.map((p) => [...p]) }
    : { type: 'Polygon' as const, coordinates: [[...points, points[0]!].map((p) => [...p])] }
}

/** The middle of the bounding box, as the asset's anchor, rounded like every stored coordinate. */
export function sketchAnchor(local: readonly Local[]): LonLat {
  const xs = local.map((p) => p[0])
  const ys = local.map((p) => p[1])
  return roundLonLat(
    toLonLat((Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...ys) + Math.max(...ys)) / 2),
  )
}

/** Ray casting: is the point inside the ring (open or closed) of [lon, lat] pairs. */
export function insideRing(point: LonLat, ring: readonly (readonly number[])[]): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]!
    const [xj, yj] = ring[j]!
    if (
      yi! > point[1] !== yj! > point[1] &&
      point[0] < ((xj! - xi!) * (point[1] - yi!)) / (yj! - yi!) + xi!
    ) {
      inside = !inside
    }
  }
  return inside
}

/** Where the shape came from: tracing Sentinel-2 is approximate and says so; anything else is the operator's own drawing. */
export function provenanceFor(basemap: Basemap) {
  return basemap === 'sentinel2'
    ? {
        source: 'SENTINEL2_EOX',
        accuracy: 'APPROXIMATE',
        note: 'Traced from 10 m satellite imagery: approximate, not a survey',
      }
    : { source: 'OPERATOR', accuracy: 'APPROXIMATE', note: 'Drawn in the layout designer' }
}

/** The next free "<Label> n" name and "<CODE>-nn" code among the assets of the draft. */
export function nextIdentity(
  type: Pick<AssetType, 'code' | 'label'>,
  assets: readonly LayoutAsset[],
) {
  const same = assets.filter((a) => a.typeCode === type.code).length
  const taken = new Set(assets.flatMap((a) => [String(a.name ?? ''), String(a.code ?? '')]))
  let n = same + 1
  while (taken.has(`${type.label} ${n}`) || taken.has(`${type.code}-${String(n).padStart(2, '0')}`))
    n++
  return { name: `${type.label} ${n}`, code: `${type.code}-${String(n).padStart(2, '0')}` }
}
