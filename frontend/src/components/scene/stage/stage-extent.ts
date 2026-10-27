import { fromLonLat, type Local } from '@/lib/geo'
import type { Port } from '@/lib/api/schemas'

/** Land is padded by this much on every side. */
export const LAND_PAD_M = 300
/** How far the sea reaches from the quay line. */
export const WATER_DEPTH_M = 2000
/** Built-in extent (local metres) used when the port record has no land extent: the Westports site. */
const FALLBACK = { x0: -3500, x1: 6000, y0: 0, y1: 1500 }

export interface StageExtent {
  /** Local bounding box of the land extent. */
  land: { x0: number; x1: number; y0: number; y1: number }
  /** Quay line in local metres, sorted by x. */
  quay: Local[]
  /** Land plane polygon: the padded land box, cut at the quay line. */
  landPolygon: Local[]
  /** Sea polygon: the padded land width, from the quay line out to WATER_DEPTH_M. */
  waterPolygon: Local[]
  /** Padded x range shared by land and water. */
  x0: number
  x1: number
}

type Position = readonly number[]

function localRing(ring: readonly Position[]): Local[] {
  return ring.map((p) => fromLonLat(p[0], p[1]))
}

function box(points: readonly Local[]) {
  const xs = points.map((p) => p[0])
  const ys = points.map((p) => p[1])
  return { x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) }
}

/** Height of the quay line at x: interpolated between its points, continued straight past the ends. */
export function quayY(quay: readonly Local[], x: number): number {
  if (quay.length === 0) return 0
  if (quay.length === 1) return quay[0][1]
  let i = 0
  while (i < quay.length - 2 && x > quay[i + 1][0]) i++
  const [ax, ay] = quay[i]
  const [bx, by] = quay[i + 1]
  return bx === ax ? ay : ay + ((by - ay) * (x - ax)) / (bx - ax)
}

/** Points of the quay line inside (x0, x1), plus the two padded ends. */
function quayBetween(quay: readonly Local[], x0: number, x1: number): Local[] {
  const inside = quay.filter((p) => p[0] > x0 && p[0] < x1)
  return [[x0, quayY(quay, x0)], ...inside, [x1, quayY(quay, x1)]]
}

/** Land, quay and sea of the port in local metres. Needs the active frame (usePortFrame). */
export function stageExtent(port: Pick<Port, 'landExtent' | 'quayLine'> | undefined): StageExtent {
  const ring = port?.landExtent?.coordinates[0]
  const land = ring && ring.length > 2 ? box(localRing(ring)) : FALLBACK
  const quayRaw = port?.quayLine?.coordinates
  const quay: Local[] =
    quayRaw && quayRaw.length > 1
      ? localRing(quayRaw).sort((a, b) => a[0] - b[0])
      : [
          [land.x0, 0],
          [land.x1, 0],
        ]
  const x0 = land.x0 - LAND_PAD_M
  const x1 = land.x1 + LAND_PAD_M
  const line = quayBetween(quay, x0, x1)
  const top = land.y1 + LAND_PAD_M
  const landPolygon: Local[] = [[x0, top], [x1, top], ...[...line].reverse()]
  const waterPolygon: Local[] = [
    ...line,
    [x1, quayY(quay, x1) - WATER_DEPTH_M],
    [x0, quayY(quay, x0) - WATER_DEPTH_M],
  ]
  return { land, quay, landPolygon, waterPolygon, x0, x1 }
}

/** Scene-metre box (x0..x1, z0..z1) of the land + sea, for fitting the "Whole port" view. */
export function landSceneBox(e: StageExtent) {
  return {
    x0: e.x0,
    x1: e.x1,
    z0: -(e.land.y1 + LAND_PAD_M),
    z1: -Math.min(...e.quay.map((p) => p[1])) + 0,
  }
}
