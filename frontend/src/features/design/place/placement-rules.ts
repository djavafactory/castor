import { fromLonLat, type Local } from '@/lib/geo'
import type { AssetType, LayoutAsset, Port } from '@/lib/api'
import { formatMessage } from '@/lib/validation/rules'
import {
  clippedArea,
  distanceToSegment,
  pointInPolygon,
  ringCovers,
  ringToLineDistance,
} from './plan-geometry'

/**
 * The placement rules of DESIGNER-03 in the browser, so the ghost can say why a spot is refused before anything is
 * sent. Same order, thresholds and wording (lib/validation/rules) as the server's LayoutValidator; the server checks
 * again on save and has the last word.
 */

const TOLERANCE_M = 0.01
const OVERLAP_AREA_M2 = 0.01
const BERTH_OVERLAP_M = 0.05
const RAIL_CRANE_MAX_OFFSET_M = 30
const AREA_DECLARATIONS = new Set(['TERMINAL_BOUNDARY', 'SURFACE_AREA'])
const EXCLUSION_ZONE = 'EXCLUSION_ZONE'

/** The port outlines in local metres; land and quay are null for a port that has none. */
export interface PlacementSite {
  extent: Local[]
  land: Local[] | null
  quay: Local[] | null
}

/** One asset of the draft as the rules see it. */
export interface DraftShape {
  id: string
  name: string
  typeCode: string
  ring: Local[] | null
  line: Local[] | null
  allowedTypes: string[]
}

/** What is being placed. A footprint has a ring, a drawn line has a line. */
export interface Candidate {
  name: string
  type: AssetType
  ring: Local[] | null
  line: Local[] | null
  /** The asset itself when it is being moved, so it never meets itself. */
  selfId?: string
  overridden?: boolean
}

type Position = [number, number]

function openRing(coordinates: unknown): Local[] {
  const ring = (Array.isArray(coordinates) ? coordinates[0] : null) as Position[] | null
  if (!ring) return []
  const pts = ring.map(([lon, lat]) => fromLonLat(lon, lat))
  const first = pts[0]
  const last = pts[pts.length - 1]
  return first && last && first[0] === last[0] && first[1] === last[1] ? pts.slice(0, -1) : pts
}

export function siteOf(port: Port): PlacementSite {
  return {
    extent: openRing(port.extent.coordinates),
    land: port.landExtent ? openRing(port.landExtent.coordinates) : null,
    quay: port.quayLine
      ? (port.quayLine.coordinates as Position[]).map(([lon, lat]) => fromLonLat(lon, lat))
      : null,
  }
}

export function draftShapes(assets: readonly LayoutAsset[]): DraftShape[] {
  return assets.flatMap((a) => {
    const geometry = a.geometry as { type?: string; coordinates?: unknown } | undefined
    if (!geometry) return []
    const details = a.details as { zone?: { allowedTypes?: string[] } } | undefined
    return [
      {
        id: a.id,
        name: String(a.name ?? a.code ?? a.typeCode),
        typeCode: a.typeCode,
        ring: geometry.type === 'Polygon' ? openRing(geometry.coordinates) : null,
        line:
          geometry.type === 'LineString'
            ? (geometry.coordinates as Position[]).map(([lon, lat]) => fromLonLat(lon, lat))
            : null,
        allowedTypes: details?.zone?.allowedTypes ?? [],
      },
    ]
  })
}

/** Which side of the quay line is water: the side the land is not on, found per segment like the server does. */
function waterSide(site: PlacementSite): { a: Local; b: Local; landSign: number }[] {
  if (!site.quay || !site.land) return []
  const out: { a: Local; b: Local; landSign: number }[] = []
  for (let i = 0; i + 1 < site.quay.length; i++) {
    const a = site.quay[i]!
    const b = site.quay[i + 1]!
    const len = Math.hypot(b[0] - a[0], b[1] - a[1])
    if (len === 0) continue
    const mid: Local = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
    const nx = -(b[1] - a[1]) / len
    const ny = (b[0] - a[0]) / len
    const left = pointInPolygon([mid[0] + nx * 2, mid[1] + ny * 2], site.land)
    const right = pointInPolygon([mid[0] - nx * 2, mid[1] - ny * 2], site.land)
    if (left !== right) out.push({ a, b, landSign: left ? 1 : -1 })
  }
  return out
}

function isWater(p: Local, segments: ReturnType<typeof waterSide>): boolean {
  let nearest: (typeof segments)[number] | null = null
  let best = Infinity
  for (const s of segments) {
    const d = distanceToSegment(p, s.a, s.b)
    if (d < best) {
      best = d
      nearest = s
    }
  }
  if (!nearest || best <= TOLERANCE_M) return false
  const c =
    (nearest.b[0] - nearest.a[0]) * (p[1] - nearest.a[1]) -
    (nearest.b[1] - nearest.a[1]) * (p[0] - nearest.a[0])
  return Math.sign(c) !== nearest.landSign
}

function centroid(ring: readonly Local[]): Local {
  const n = ring.length
  return [ring.reduce((s, p) => s + p[0], 0) / n, ring.reduce((s, p) => s + p[1], 0) / n]
}

function xRange(points: readonly Local[]): [number, number] {
  const xs = points.map((p) => p[0])
  return [Math.min(...xs), Math.max(...xs)]
}

/** The points of the candidate that stand for it in the quay checks: its ring, or its line. */
const outline = (c: Candidate): Local[] => c.ring ?? c.line ?? []

/** Does the drawn line cross or lie inside a zone? */
function lineMeetsRing(line: readonly Local[], ring: readonly Local[]): boolean {
  return ringToLineDistance(ring, line) === 0
}

/**
 * The reason a candidate cannot be placed, in the order of the server's rules (extent, water, land, exclusion zone,
 * then overlap, berth overlap or berth for rail cranes), or null when it can.
 */
export function placementProblem(
  c: Candidate,
  site: PlacementSite,
  types: ReadonlyMap<string, AssetType>,
  shapes: readonly DraftShape[],
): string | null {
  const points = outline(c)
  if (points.length === 0) return null
  if (site.extent.length > 2 && !points.every((p) => pointInPolygon(p, site.extent))) {
    return formatMessage('OUT_OF_PORT', 'default', { name: c.name })
  }
  const flags = c.type.flags
  if (!flags.mayExtendOverWater) {
    const segments = waterSide(site)
    if (segments.length > 0 && points.some((p) => isWater(p, segments))) {
      return formatMessage('ON_WATER', 'default')
    }
    if (
      site.land &&
      !(c.ring
        ? ringCovers(site.land, c.ring, TOLERANCE_M)
        : points.every((p) => pointInPolygon(p, site.land!)))
    ) {
      return formatMessage('OUTSIDE_LAND', 'default')
    }
  }

  const others = shapes.filter((s) => s.id !== c.selfId)
  const exempt = flags.zone || AREA_DECLARATIONS.has(c.type.code) || c.overridden
  if (!exempt) {
    const hits = others
      .filter(
        (s) => s.typeCode === EXCLUSION_ZONE && s.ring && !s.allowedTypes.includes(c.type.code),
      )
      .map((s) => ({
        s,
        area: c.ring ? clippedArea(s.ring!, c.ring) : 0,
        crossing: c.line ? lineMeetsRing(c.line, s.ring!) : false,
      }))
      .filter((h) => (c.line ? h.crossing : h.area > OVERLAP_AREA_M2))
      .sort((a, b) => b.area - a.area)
    if (hits.length > 0)
      return formatMessage('IN_EXCLUSION_ZONE', 'default', { zone: hits[0]!.s.name })
  }

  const berths = others.filter(
    (s) => types.get(s.typeCode)?.geometryKind === 'QUAY_SEGMENT' && s.line,
  )
  if (c.type.geometryKind === 'QUAY_SEGMENT') {
    const [min, max] = xRange(points)
    for (const b of berths) {
      const [bMin, bMax] = xRange(b.line!)
      if (Math.min(max, bMax) - Math.max(min, bMin) > BERTH_OVERLAP_M) {
        return formatMessage('BERTH_OVERLAP', 'default', { other: b.name })
      }
    }
  } else if (flags.mustBeOnBerth && c.ring) {
    const [cx] = centroid(c.ring)
    const stands = berths.some((b) => {
      const [bMin, bMax] = xRange(b.line!)
      return (
        cx >= bMin && cx <= bMax && ringToLineDistance(c.ring!, b.line!) <= RAIL_CRANE_MAX_OFFSET_M
      )
    })
    if (!stands) return formatMessage('CRANE_OFF_BERTH', 'default')
  } else if (flags.solid && c.ring) {
    for (const s of others) {
      if (!s.ring || !types.get(s.typeCode)?.flags.solid) continue
      if (clippedArea(s.ring, c.ring) > OVERLAP_AREA_M2) {
        return formatMessage('OVERLAP', 'default', { other: s.name })
      }
    }
  }
  return null
}
