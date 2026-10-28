import { selfIntersection, polygonArea, type Local } from '@/lib/geo'
import type { AssetType } from '@/lib/api'
import { formatMessage } from '@/lib/validation/rules'
import { MIN_AREA_M2 } from '@/features/design/draw/draw-rules'
import { ringsOfShape, type EditShape, type EditSubject } from './edit-model'

/** Same numbers as the server's LayoutValidator, so the browser refuses what the server would refuse. */
const OVERLAP_MIN_M = 0.05
const RAIL_CRANE_MAX_OFFSET_M = 30
const WATER_TOLERANCE_M = 0.01
const AREA_DECLARATIONS = new Set(['TERMINAL_BOUNDARY', 'SURFACE_AREA'])
const EXCLUSION_ZONE = 'EXCLUSION_ZONE'

export const CROSSES_ITSELF = 'the outline would cross itself'

/** Another asset of the draft, in local metres, as far as the placement rules need it. */
export interface Neighbour {
  id: string
  name: string
  typeCode: string
  rings: Local[][]
  closed: boolean
  /** The types an exclusion zone lets in. */
  allowedTypes: string[]
}

export interface RuleContext {
  type: AssetType
  /** The port extent and the land, as rings in local metres; null when the port has none. */
  extent: Local[] | null
  land: Local[] | null
  hasQuay: boolean
  neighbours: readonly Neighbour[]
  types: ReadonlyMap<string, AssetType>
  /** A documented override of the exclusion zone rule. */
  overridden: boolean
}

export type Verdict = { ok: true } | { ok: false; reason: string }

const ok: Verdict = { ok: true }
const refuse = (reason: string): Verdict => ({ ok: false, reason })

/** Even-odd ray casting: is the point strictly inside the ring. */
export function pointInRing(point: Local, ring: readonly Local[]): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]!
    const [xj, yj] = ring[j]!
    if (
      yi > point[1] !== yj > point[1] &&
      point[0] < ((xj - xi) * (point[1] - yi)) / (yj - yi) + xi
    ) {
      inside = !inside
    }
  }
  return inside
}

const cross = (o: Local, a: Local, b: Local) =>
  (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

/** True when the two segments cross at a single interior point of both (touching and sharing an end do not count). */
function properCrossing(a: Local, b: Local, c: Local, d: Local): boolean {
  const eps = 1e-9
  const d1 = cross(c, d, a)
  const d2 = cross(c, d, b)
  const d3 = cross(a, b, c)
  const d4 = cross(a, b, d)
  return (
    ((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
    ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))
  )
}

function centroid(ring: readonly Local[]): Local {
  let x = 0
  let y = 0
  for (const p of ring) {
    x += p[0]
    y += p[1]
  }
  return [x / ring.length, y / ring.length]
}

function edges(ring: readonly Local[], closed: boolean): [Local, Local][] {
  const out: [Local, Local][] = []
  const count = closed ? ring.length : ring.length - 1
  for (let i = 0; i < count; i++) out.push([ring[i]!, ring[(i + 1) % ring.length]!])
  return out
}

/**
 * Whether two shapes share more than an edge. Polygons overlap when an edge of one crosses an edge of the other, or when
 * one lies inside the other; a line meets a polygon when it crosses its outline or starts inside it.
 */
export function shapesMeet(
  a: { rings: Local[][]; closed: boolean },
  b: { rings: Local[][]; closed: boolean },
): boolean {
  for (const ra of a.rings) {
    for (const rb of b.rings) {
      for (const [p, q] of edges(ra, a.closed)) {
        for (const [r, s] of edges(rb, b.closed)) {
          if (properCrossing(p, q, r, s)) return true
        }
      }
    }
  }
  // Touching along an edge is not meeting, so only points well inside a shape are probed: the middle of a polygon, and
  // the vertices and middles of a line
  const inside = (
    outer: { rings: Local[][]; closed: boolean },
    inner: { rings: Local[][]; closed: boolean },
  ) =>
    outer.closed &&
    inner.rings.some((ring) => {
      const probes: Local[] = inner.closed
        ? [centroid(ring)]
        : [
            ...ring,
            ...edges(ring, false).map(([p, q]): Local => [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2]),
          ]
      return outer.rings.some((o) => probes.some((p) => pointInRing(p, o)))
    })
  return inside(a, b) || inside(b, a)
}

function bounds(rings: Local[][]) {
  const xs = rings.flat().map((p) => p[0])
  return { minX: Math.min(...xs), maxX: Math.max(...xs) }
}

function distanceToSegment(p: Local, a: Local, b: Local): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const len2 = dx * dx + dy * dy
  const t =
    len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2))
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy))
}

function dimensionProblem(subject: EditSubject, shape: EditShape): string | null {
  const { limits } = subject
  const min = (label: string, value: number, limit: number) =>
    value < limit ? formatMessage('DIM_OUT_OF_RANGE', 'min', { label, limit }) : null
  const max = (label: string, value: number, limit: number | null) =>
    limit !== null && value > limit
      ? formatMessage('DIM_OUT_OF_RANGE', 'max', { label, limit })
      : null
  switch (shape.kind) {
    case 'rect':
      return (
        min('Length', shape.length, limits.minLength) ??
        min('Width', shape.width, limits.minWidth) ??
        max('Length', shape.length, limits.maxLength) ??
        max('Width', shape.width, limits.maxWidth)
      )
    case 'circle':
      return (
        min('Radius', shape.radius, limits.minRadius) ??
        max('Radius', shape.radius, limits.maxRadius)
      )
    case 'berth': {
      const length = Math.hypot(shape.b[0] - shape.a[0], shape.b[1] - shape.a[1])
      return min('Length', length, limits.minLength) ?? max('Length', length, limits.maxLength)
    }
    default:
      return null
  }
}

/**
 * Checks an edited shape against the rules the server applies to a save, in the server's order: a self-crossing outline,
 * the port extent, the size limits, water and land, exclusion zones, then overlaps (or the berth rules). Returns the first
 * failure with the catalogue's wording.
 */
export function checkEdit(subject: EditSubject, shape: EditShape, ctx: RuleContext): Verdict {
  const name = subject.name
  const { rings, closed } = ringsOfShape(shape)
  const points = rings.flat()

  if (shape.kind === 'polygon' && selfIntersection(shape.points)) return refuse(CROSSES_ITSELF)
  if (ctx.extent && points.some((p) => !pointInRing(p, ctx.extent!))) {
    return refuse(formatMessage('OUT_OF_PORT', 'default', { name }))
  }
  if (shape.kind === 'polygon' && polygonArea(shape.points) < MIN_AREA_M2) {
    return refuse(formatMessage('TOO_SMALL', 'default'))
  }
  const dimension = dimensionProblem(subject, shape)
  if (dimension) return refuse(dimension)

  const overWater = ctx.type.flags.mayExtendOverWater
  if (!overWater && ctx.hasQuay && points.some((p) => p[1] < -WATER_TOLERANCE_M)) {
    return refuse(formatMessage('ON_WATER', 'default'))
  }
  if (!overWater && ctx.land && points.some((p) => !pointInRing(p, ctx.land!))) {
    return refuse(formatMessage('OUTSIDE_LAND', 'default'))
  }

  const mine = { rings, closed }
  const exempt = ctx.type.flags.zone || AREA_DECLARATIONS.has(subject.typeCode) || ctx.overridden
  if (!exempt) {
    const zone = ctx.neighbours.find(
      (n) =>
        n.typeCode === EXCLUSION_ZONE &&
        !n.allowedTypes.includes(subject.typeCode) &&
        shapesMeet(mine, n),
    )
    if (zone) return refuse(formatMessage('IN_EXCLUSION_ZONE', 'default', { zone: zone.name }))
  }

  const berths = ctx.neighbours.filter(
    (n) => ctx.types.get(n.typeCode)?.geometryKind === 'QUAY_SEGMENT',
  )
  if (ctx.type.geometryKind === 'QUAY_SEGMENT') {
    const own = bounds(rings)
    for (const other of berths) {
      const their = bounds(other.rings)
      const along = Math.min(own.maxX, their.maxX) - Math.max(own.minX, their.minX)
      if (along > OVERLAP_MIN_M) {
        return refuse(formatMessage('BERTH_OVERLAP', 'default', { other: other.name }))
      }
    }
  } else if (ctx.type.flags.mustBeOnBerth) {
    const centre = centroid(rings[0]!)
    const stands = berths.some((berth) => {
      const { minX, maxX } = bounds(berth.rings)
      const line = berth.rings[0]!
      return (
        centre[0] >= minX &&
        centre[0] <= maxX &&
        Math.min(...edges(line, false).map(([a, b]) => distanceToSegment(centre, a, b))) <=
          RAIL_CRANE_MAX_OFFSET_M
      )
    })
    if (!stands) return refuse(formatMessage('CRANE_OFF_BERTH', 'default'))
  } else if (ctx.type.flags.solid) {
    const other = ctx.neighbours.find(
      (n) => n.closed && ctx.types.get(n.typeCode)?.flags.solid && closed && shapesMeet(mine, n),
    )
    if (other) return refuse(formatMessage('OVERLAP', 'default', { other: other.name }))
  }
  return ok
}

/** The text of the toast for a refused edit. */
export const notMoved = (reason: string) => `Not moved: ${reason}`
