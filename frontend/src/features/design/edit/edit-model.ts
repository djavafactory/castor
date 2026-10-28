import {
  circle,
  circleLocal,
  fromLonLat,
  headingToRotation,
  rectangle,
  rectangleLocal,
  toLonLat,
  type Local,
  type LonLat,
} from '@/lib/geo'
import type { AssetType, LayoutAsset } from '@/lib/api'
import { roundLonLat, sketchAnchor } from '@/features/design/draw/draw-rules'

/** What the editor handles can change about one asset, in local metres (the quay frame). */
export type EditShape =
  /** `headingDeg` is the stored true heading: it is only ever changed by a rotation, so resizing never drifts it. */
  | { kind: 'rect'; anchor: Local; headingDeg: number; length: number; width: number }
  | { kind: 'circle'; anchor: Local; radius: number }
  /** A berth: the stretch of quay line it covers, from one end to the other. */
  | { kind: 'berth'; a: Local; b: Local }
  | { kind: 'polygon'; points: Local[] }
  | { kind: 'line'; points: Local[] }

export interface EditLimits {
  minLength: number
  minWidth: number
  maxLength: number | null
  maxWidth: number | null
  minRadius: number
  maxRadius: number | null
}

/** A rectangle that a handle makes smaller than the type allows stops here; the ticket's default when the type has none. */
export const DEFAULT_MIN_SIDE_M = 2
export const MIN_RADIUS_M = 1
export const MIN_BERTH_M = 20

export interface PadLayout {
  bays: number
  rows: number
}

/** One asset of the draft as the editing handles see it. */
export interface EditSubject {
  id: string
  rowVersion: number
  name: string
  typeCode: string
  shape: EditShape
  /** Berths and rail cranes only slide along the quay (local x). */
  alongQuay: boolean
  /** A storage pad resizes by changing its bay and row pitch. */
  pad: PadLayout | null
  limits: EditLimits
  /** The stored coordinates of every vertex, so a vertex that was not touched keeps its exact value. */
  original: { local: Local; lonLat: LonLat }[]
  asset: LayoutAsset
}

const num = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null

const lonLat = (value: unknown): LonLat | null =>
  Array.isArray(value) && typeof value[0] === 'number' && typeof value[1] === 'number'
    ? [value[0], value[1]]
    : null

function positions(value: unknown): LonLat[] | null {
  if (!Array.isArray(value)) return null
  const out: LonLat[] = []
  for (const item of value) {
    const p = lonLat(item)
    if (!p) return null
    out.push(p)
  }
  return out
}

/** The vertices of the single outer ring of a Polygon, without the closing point; null for anything else. */
function polygonRing(geometry: { type?: unknown; coordinates?: unknown }): LonLat[] | null {
  if (geometry.type !== 'Polygon' || !Array.isArray(geometry.coordinates)) return null
  if (geometry.coordinates.length !== 1) return null
  const ring = positions(geometry.coordinates[0])
  if (!ring || ring.length < 4) return null
  return ring.slice(0, -1)
}

function limitsOf(type: AssetType): EditLimits {
  return {
    minLength: type.min.lengthM ?? DEFAULT_MIN_SIDE_M,
    minWidth: type.min.widthM ?? DEFAULT_MIN_SIDE_M,
    maxLength: type.max.lengthM,
    maxWidth: type.max.widthM,
    minRadius: Math.max(MIN_RADIUS_M, type.min.radiusM ?? 0),
    maxRadius: type.max.radiusM,
  }
}

function padOf(asset: LayoutAsset): PadLayout | null {
  const details = asset.details as { storagePad?: { bays?: unknown; rows?: unknown } } | undefined
  const bays = num(details?.storagePad?.bays)
  const rows = num(details?.storagePad?.rows)
  return bays !== null && rows !== null && bays > 0 && rows > 0 ? { bays, rows } : null
}

const keep = (points: readonly LonLat[]) =>
  points.map((lonLat) => ({ local: fromLonLat(lonLat[0], lonLat[1]), lonLat }))

/**
 * The editable form of an asset of the draft, or null when the handles cannot edit it: a shape the map does not
 * know, a polygon with holes, a point without a size. Needs the port frame.
 */
export function subjectOf(asset: LayoutAsset, type: AssetType | undefined): EditSubject | null {
  if (!type) return null
  const geometry = asset.geometry as { type?: unknown; coordinates?: unknown } | undefined
  if (!geometry) return null
  const base = {
    id: asset.id,
    rowVersion: asset.rowVersion,
    name: typeof asset.name === 'string' ? asset.name : '',
    typeCode: asset.typeCode,
    pad: null as PadLayout | null,
    alongQuay: type.geometryKind === 'QUAY_SEGMENT' || type.flags.mustBeOnBerth,
    limits: limitsOf(type),
    original: [] as EditSubject['original'],
    asset,
  }
  const anchorLonLat = lonLat(asset.anchor)

  switch (type.geometryKind) {
    case 'POINT':
    case 'RECT': {
      const length = num(asset.lengthM)
      const width = num(asset.widthM)
      if (!anchorLonLat || length === null || width === null) return null
      return {
        ...base,
        pad: type.code === 'STORAGE_PAD' ? padOf(asset) : null,
        shape: {
          kind: 'rect',
          anchor: fromLonLat(anchorLonLat[0], anchorLonLat[1]),
          headingDeg: num(asset.headingDeg) ?? 0,
          length,
          width,
        },
      }
    }
    case 'CIRCLE': {
      const radius = num(asset.radiusM)
      if (!anchorLonLat || radius === null) return null
      return {
        ...base,
        shape: { kind: 'circle', anchor: fromLonLat(anchorLonLat[0], anchorLonLat[1]), radius },
      }
    }
    case 'QUAY_SEGMENT': {
      const line = geometry.type === 'LineString' ? positions(geometry.coordinates) : null
      if (!line || line.length !== 2) return null
      const original = keep(line)
      return {
        ...base,
        original,
        limits: { ...base.limits, minLength: Math.max(MIN_BERTH_M, base.limits.minLength) },
        shape: { kind: 'berth', a: original[0]!.local, b: original[1]!.local },
      }
    }
    case 'LINE': {
      const line = geometry.type === 'LineString' ? positions(geometry.coordinates) : null
      if (!line || line.length < 2) return null
      const original = keep(line)
      return { ...base, original, shape: { kind: 'line', points: original.map((o) => o.local) } }
    }
    case 'POLYGON': {
      const ring = polygonRing(geometry)
      if (!ring || ring.length < 3) return null
      const original = keep(ring)
      return {
        ...base,
        original,
        shape: { kind: 'polygon', points: original.map((o) => o.local) },
      }
    }
    default:
      return null
  }
}

/** The rings the map draws for a shape, in local metres; `closed` says polygon or line. */
export function ringsOfShape(shape: EditShape): { rings: Local[][]; closed: boolean } {
  switch (shape.kind) {
    case 'rect':
      return {
        rings: [
          rectangleLocal(
            shape.anchor,
            headingToRotation(shape.headingDeg),
            shape.length,
            shape.width,
          ),
        ],
        closed: true,
      }
    case 'circle':
      return { rings: [circleLocal(shape.anchor, shape.radius)], closed: true }
    case 'berth':
      return { rings: [[shape.a, shape.b]], closed: false }
    case 'polygon':
      return { rings: [shape.points], closed: true }
    case 'line':
      return { rings: [shape.points], closed: false }
  }
}

/** Local metres to the stored WGS84 coordinate: the same rounding as every drawn point. */
export const storedLonLat = (p: Local): LonLat => roundLonLat(toLonLat(p[0], p[1]))

const closeRing = (points: readonly LonLat[]) => [...points, points[0]!].map((p) => [...p])

/** The vertices of an edited shape as stored coordinates: a vertex that did not move keeps its exact value. */
function lonLats(subject: EditSubject, points: readonly Local[]): LonLat[] {
  return points.map((p) => {
    const same = subject.original.find((o) => o.local[0] === p[0] && o.local[1] === p[1])
    return same ? same.lonLat : storedLonLat(p)
  })
}

/**
 * The PATCH body that stores an edited shape. A rectangle or circle is rebuilt from its anchor, heading and size (the
 * server rebuilds it the same way and refuses a footprint that is more than 1 cm off); a polygon, line or berth is
 * stored as drawn.
 */
export function patchOf(subject: EditSubject, shape: EditShape): Record<string, unknown> {
  switch (shape.kind) {
    case 'rect': {
      const anchor = storedLonLat(shape.anchor)
      const ring = rectangle(anchor, shape.headingDeg, shape.length, shape.width).map(roundLonLat)
      const changes: Record<string, unknown> = {
        anchor,
        headingDeg: shape.headingDeg,
        lengthM: shape.length,
        widthM: shape.width,
        geometry: { type: 'Polygon', coordinates: [closeRing(ring)] },
      }
      if (subject.pad) {
        const details = (subject.asset.details ?? {}) as Record<string, unknown>
        changes.details = {
          ...details,
          storagePad: {
            ...(details.storagePad as Record<string, unknown>),
            bayPitchM: round2(shape.length / subject.pad.bays),
            rowPitchM: round2(shape.width / subject.pad.rows),
          },
        }
      }
      return changes
    }
    case 'circle': {
      const anchor = storedLonLat(shape.anchor)
      const ring = circle(anchor, shape.radius).map(roundLonLat)
      return {
        anchor,
        radiusM: shape.radius,
        geometry: { type: 'Polygon', coordinates: [closeRing(ring)] },
      }
    }
    case 'berth': {
      const line = lonLats(subject, [shape.a, shape.b])
      return {
        anchor: storedLonLat([(shape.a[0] + shape.b[0]) / 2, (shape.a[1] + shape.b[1]) / 2]),
        lengthM: round2(Math.hypot(shape.b[0] - shape.a[0], shape.b[1] - shape.a[1])),
        geometry: { type: 'LineString', coordinates: line.map((p) => [...p]) },
      }
    }
    case 'line': {
      return {
        geometry: {
          type: 'LineString',
          coordinates: lonLats(subject, shape.points).map((p) => [...p]),
        },
      }
    }
    case 'polygon': {
      const ring = lonLats(subject, shape.points)
      return {
        anchor: sketchAnchor(shape.points),
        geometry: { type: 'Polygon', coordinates: [closeRing(ring)] },
      }
    }
  }
}

/** The stored values of the fields a patch changes: PATCHing them back is the undo. */
export function inverseOf(asset: LayoutAsset, changes: Record<string, unknown>) {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(changes)) out[key] = asset[key]
  return out
}

export const round2 = (value: number) => Math.round(value * 100) / 100
