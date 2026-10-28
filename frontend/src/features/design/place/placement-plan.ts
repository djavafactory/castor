import { circleLocal, rectangleLocal, rotationToHeading, toLonLat, type Local } from '@/lib/geo'
import type { AssetType, NewAsset } from '@/lib/api'
import { roundLonLat } from '../draw/draw-rules'
import { nearestOnPolyline, pointInPolygon, sliceByChainage } from './plan-geometry'
import type { DraftShape, PlacementSite } from './placement-rules'

/** A rail quay crane stands on its rail, this far inland of the berth line (gauge 16 m plus the apron). */
export const CRANE_RAIL_OFFSET_M = 10
/** Default outlines of a draw type dropped from the palette: a 60 x 40 m polygon or an 80 m line. */
export const DROPPED_POLYGON_M: [number, number] = [60, 40]
export const DROPPED_LINE_M = 80

export const PLACED_NOTE = 'Placed in the layout designer'

/** Where an asset would land: everything the ghost shows and the save sends. */
export interface PlacementPlan {
  type: AssetType
  /** Local position of the anchor and the turn, degrees counter-clockwise from local +X. */
  anchor: Local
  rotationDeg: number
  ring: Local[] | null
  line: Local[] | null
  lengthM: number
  widthM: number
  heightM: number
  /** True when the position follows the quay or the crane rail instead of the pointer. */
  snapped: boolean
}

/** Heading from true north, 0 up to 360. */
export function headingOf(rotationDeg: number): number {
  const h = rotationToHeading(rotationDeg) % 360
  return Math.round((h < 0 ? h + 360 : h) * 1000) / 1000
}

/** R adds 15 degrees and wraps into -180..180. */
export function rotated(rotationDeg: number, step = 15): number {
  const next = ((((rotationDeg + step + 180) % 360) + 360) % 360) - 180
  return next === -180 ? 180 : next
}

const num = (v: number | null | undefined, fallback: number) => (v == null ? fallback : v)

/** The unit normal of a line direction that points inland (towards the land), by testing the land outline. */
function inlandNormal(at: Local, direction: Local, site: PlacementSite): Local {
  const left: Local = [-direction[1], direction[0]]
  if (!site.land) return left
  const probe: Local = [at[0] + left[0] * 3, at[1] + left[1] * 3]
  return pointInPolygon(probe, site.land) ? left : [-left[0], -left[1]]
}

/**
 * The plan for a type with the pointer at `cursor`. Berths snap to the quay line and slide along it; rail quay cranes
 * snap to the crane rail beside the nearest berth; draw types dropped on the map get their default outline.
 */
export function planPlacement(
  type: AssetType,
  cursor: Local,
  rotationDeg: number,
  site: PlacementSite,
  shapes: readonly DraftShape[],
  types: ReadonlyMap<string, AssetType>,
): PlacementPlan {
  const d = type.defaults
  const base = { type, rotationDeg, heightM: num(d.heightM, 1), snapped: false }

  if (type.geometryKind === 'QUAY_SEGMENT') {
    const length = num(d.lengthM, 100)
    const quay = site.quay && nearestOnPolyline(cursor, site.quay)
    if (site.quay && quay) {
      const total = site.quay.reduce(
        (sum, p, i) =>
          i === 0
            ? 0
            : sum + Math.hypot(p[0] - site.quay![i - 1]![0], p[1] - site.quay![i - 1]![1]),
        0,
      )
      const from = Math.max(0, Math.min(quay.chainage - length / 2, total - length))
      const line = sliceByChainage(site.quay, from, from + length)
      if (line.length >= 2) {
        const mid = line[Math.floor(line.length / 2)]!
        return { ...base, anchor: mid, line, ring: null, lengthM: length, widthM: 0, snapped: true }
      }
    }
    const half = length / 2
    const line: Local[] = [
      [cursor[0] - half, cursor[1]],
      [cursor[0] + half, cursor[1]],
    ]
    return { ...base, anchor: cursor, line, ring: null, lengthM: length, widthM: 0 }
  }

  if (type.placementMode === 'DRAW') {
    const a = (rotationDeg * Math.PI) / 180
    if (type.geometryKind === 'LINE') {
      const half = DROPPED_LINE_M / 2
      const line: Local[] = [
        [cursor[0] - Math.cos(a) * half, cursor[1] - Math.sin(a) * half],
        [cursor[0] + Math.cos(a) * half, cursor[1] + Math.sin(a) * half],
      ]
      return {
        ...base,
        anchor: cursor,
        line,
        ring: null,
        lengthM: DROPPED_LINE_M,
        widthM: num(d.lineWidthM, 1),
      }
    }
    const [l, w] = DROPPED_POLYGON_M
    return {
      ...base,
      anchor: cursor,
      ring: rectangleLocal(cursor, rotationDeg, l, w),
      line: null,
      lengthM: l,
      widthM: w,
    }
  }

  if (type.geometryKind === 'CIRCLE') {
    const radius = num(d.radiusM, 5)
    return {
      ...base,
      anchor: cursor,
      ring: circleLocal(cursor, radius),
      line: null,
      lengthM: radius * 2,
      widthM: radius * 2,
    }
  }

  const length = num(d.lengthM, 1)
  const width = num(d.widthM, 1)
  if (type.flags.mustBeOnBerth) {
    const berths = shapes.filter(
      (s) => types.get(s.typeCode)?.geometryKind === 'QUAY_SEGMENT' && s.line,
    )
    let best: { anchor: Local; angle: number; distance: number } | null = null
    for (const b of berths) {
      const hit = nearestOnPolyline(cursor, b.line!)
      if (!hit) continue
      const distance = Math.hypot(cursor[0] - hit.point[0], cursor[1] - hit.point[1])
      if (best && distance >= best.distance) continue
      const normal = inlandNormal(hit.point, hit.direction, site)
      best = {
        anchor: [
          hit.point[0] + normal[0] * CRANE_RAIL_OFFSET_M,
          hit.point[1] + normal[1] * CRANE_RAIL_OFFSET_M,
        ],
        angle: (Math.atan2(hit.direction[1], hit.direction[0]) * 180) / Math.PI,
        distance,
      }
    }
    if (best) {
      return {
        ...base,
        anchor: best.anchor,
        rotationDeg: best.angle,
        ring: rectangleLocal(best.anchor, best.angle, length, width),
        line: null,
        lengthM: length,
        widthM: width,
        snapped: true,
      }
    }
  }
  return {
    ...base,
    anchor: cursor,
    ring: rectangleLocal(cursor, rotationDeg, length, width),
    line: null,
    lengthM: length,
    widthM: width,
  }
}

/** The body of POST asset: the anchor, heading and size; the server builds the footprint of point and rect types. */
export function placementBody(plan: PlacementPlan, name: string): NewAsset {
  const { type } = plan
  const anchor = roundLonLat(toLonLat(plan.anchor[0], plan.anchor[1]))
  const body: NewAsset = {
    typeCode: type.code,
    name,
    anchor,
    headingDeg: headingOf(plan.rotationDeg),
    provenance: { source: 'OPERATOR', accuracy: 'APPROXIMATE', note: PLACED_NOTE },
  }
  if (plan.line) {
    body.geometry = {
      type: 'LineString',
      coordinates: plan.line.map(([x, y]) => roundLonLat(toLonLat(x, y))),
    }
  } else if (plan.ring) {
    // point, rect and circle footprints are rebuilt by the server; the ring shows the asset at once while it saves
    const ring = plan.ring.map(([x, y]) => roundLonLat(toLonLat(x, y)))
    body.geometry = { type: 'Polygon', coordinates: [[...ring, ring[0]!]] }
  }
  switch (type.geometryKind) {
    case 'CIRCLE':
      body.radiusM = plan.lengthM / 2
      break
    case 'POINT':
    case 'RECT':
      body.lengthM = plan.lengthM
      body.widthM = plan.widthM
      break
    case 'QUAY_SEGMENT':
      body.lengthM = plan.lengthM
      break
    case 'LINE':
      body.lineWidthM = plan.widthM
      break
    default:
      break
  }
  if (type.defaults.heightM != null) body.heightM = type.defaults.heightM
  return body
}
