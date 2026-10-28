import { layerOf } from '@/components/scene/layers'
import { fromLonLat, type Local } from '@/lib/geo'
import type { AssetType } from '@/lib/api/schemas'
import type { LocalBox } from './map-view'

/** One thing the 2D map draws, already in local metres. Built once per layout, drawn on every frame. */
export interface MapShape {
  /** Asset id, or the OpenStreetMap reference of a background shape. */
  id: string
  source: 'asset' | 'osm'
  typeCode: string
  /** Draw order, bottom to top (see {@link drawRank}). */
  rank: number
  /** The rings of a polygon, or the one line of a line asset. */
  rings: Local[][]
  closed: boolean
  box: LocalBox
  /** Fill and outline colour (a hex string from the type or the palette). */
  colour: string
  name: string
  anchor: Local
  /** The shorter side of the shape, metres; null for lines. */
  minSideM: number | null
  /** Length along the quay of a berth, metres. */
  berthLengthM: number | null
  /** Width of a line asset, metres. */
  lineWidthM: number | null
}

/** Draw order bottom to top: terminal boundaries, surface areas, roads, flat assets, pads, buildings, others, berths, quay cranes. */
export const RANK = {
  terminal: 0,
  surface: 1,
  road: 2,
  flat: 3,
  pad: 4,
  building: 5,
  other: 6,
  berth: 7,
  crane: 8,
} as const

const LAYER_RANK: Record<string, number> = {
  terminals: RANK.terminal,
  'surface-areas': RANK.surface,
  roads: RANK.road,
  'storage-pads': RANK.pad,
  buildings: RANK.building,
  berths: RANK.berth,
  'quay-cranes': RANK.crane,
}

export function drawRank(
  type: Pick<AssetType, 'code' | 'category' | 'flags'> | undefined,
  code: string,
): number {
  const layer = layerOf(code, type?.category)
  if (layer !== undefined && layer in LAYER_RANK) return LAYER_RANK[layer]
  return type?.flags.flat ? RANK.flat : RANK.other
}

type Position = readonly number[]

function toLocal(position: Position): Local {
  return fromLonLat(position[0], position[1])
}

function ringsOf(geometry: { type: string; coordinates: unknown }): {
  rings: Local[][]
  closed: boolean
} | null {
  const c = geometry.coordinates
  if (!Array.isArray(c)) return null
  switch (geometry.type) {
    case 'Polygon':
      return { rings: (c as Position[][]).map((ring) => ring.map(toLocal)), closed: true }
    case 'MultiPolygon':
      return {
        rings: (c as Position[][][]).flatMap((polygon) => polygon.map((ring) => ring.map(toLocal))),
        closed: true,
      }
    case 'LineString':
      return { rings: [(c as Position[]).map(toLocal)], closed: false }
    case 'MultiLineString':
      return { rings: (c as Position[][]).map((line) => line.map(toLocal)), closed: false }
    default:
      return null
  }
}

export function boxOf(rings: readonly (readonly Local[])[]): LocalBox {
  let x0 = Infinity
  let x1 = -Infinity
  let y0 = Infinity
  let y1 = -Infinity
  for (const ring of rings) {
    for (const [x, y] of ring) {
      if (x < x0) x0 = x
      if (x > x1) x1 = x
      if (y < y0) y0 = y
      if (y > y1) y1 = y
    }
  }
  return { x0, x1, y0, y1 }
}

const num = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null

/** The fields of an asset of the draft that the map reads; the document keeps all of them, loosely typed. */
export interface MapAssetSource {
  id: string
  typeCode: string
  name?: unknown
  geometry?: unknown
  anchor?: unknown
  lengthM?: unknown
  widthM?: unknown
  radiusM?: unknown
  lineWidthM?: unknown
}

function isGeometry(value: unknown): value is { type: string; coordinates: unknown } {
  return typeof value === 'object' && value !== null && 'type' in value && 'coordinates' in value
}

/** The shapes of the assets of one layout version; assets of an unknown shape are left out. */
export function assetShapes(
  assets: readonly MapAssetSource[],
  types: readonly AssetType[] | undefined,
  fallbackColour: string,
): MapShape[] {
  const byCode = new Map((types ?? []).map((t) => [t.code, t]))
  const out: MapShape[] = []
  for (const a of assets) {
    if (!isGeometry(a.geometry)) continue
    const parts = ringsOf(a.geometry)
    if (!parts || parts.rings.every((r) => r.length === 0)) continue
    const type = byCode.get(a.typeCode)
    const box = boxOf(parts.rings)
    const anchor =
      Array.isArray(a.anchor) && a.anchor.length >= 2
        ? toLocal(a.anchor as Position)
        : ([(box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2] as Local)
    const length = num(a.lengthM)
    const width = num(a.widthM)
    const radius = num(a.radiusM)
    const sides = [length, width].filter((v): v is number => v !== null)
    const bboxSide = Math.min(box.x1 - box.x0, box.y1 - box.y0)
    out.push({
      id: a.id,
      source: 'asset',
      typeCode: a.typeCode,
      rank: drawRank(type, a.typeCode),
      rings: parts.rings,
      closed: parts.closed,
      box,
      colour: type?.colourHex ?? fallbackColour,
      name: typeof a.name === 'string' ? a.name : '',
      anchor,
      minSideM: !parts.closed
        ? null
        : radius !== null
          ? radius * 2
          : sides.length === 2
            ? Math.min(...sides)
            : bboxSide,
      berthLengthM: a.typeCode === 'BERTH' ? length : null,
      lineWidthM: parts.closed ? null : num(a.lineWidthM),
    })
  }
  return out.sort((p, q) => p.rank - q.rank)
}

/** The outline of one background shape (OpenStreetMap), drawn as a thin grey line. */
export interface OsmSource {
  id: string
  geometry: { type: string; coordinates: unknown }
  name?: string | null
}

export function osmShapes(features: readonly OsmSource[], colour: string): MapShape[] {
  const out: MapShape[] = []
  for (const f of features) {
    const parts = ringsOf(f.geometry)
    if (!parts) continue
    const box = boxOf(parts.rings)
    out.push({
      id: f.id,
      source: 'osm',
      typeCode: 'OSM',
      rank: -1,
      rings: parts.rings,
      closed: parts.closed,
      box,
      colour,
      name: f.name ?? '',
      anchor: [(box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2],
      minSideM: parts.closed ? Math.min(box.x1 - box.x0, box.y1 - box.y0) : null,
      berthLengthM: null,
      lineWidthM: null,
    })
  }
  return out
}

/** Labels show when the shape is big enough on screen: shorter side x k > 26 px, a berth's length x k > 50 px. */
export const LABEL_MIN_SIDE_PX = 26
export const LABEL_MIN_BERTH_PX = 50

export function labelFits(shape: MapShape, k: number): boolean {
  if (shape.name === '') return false
  if (shape.berthLengthM !== null) return shape.berthLengthM * k > LABEL_MIN_BERTH_PX
  return shape.minSideM !== null && shape.minSideM * k > LABEL_MIN_SIDE_PX
}

/** True when the shape's box touches the visible box. */
export function boxesTouch(a: LocalBox, b: LocalBox): boolean {
  return a.x0 <= b.x1 && a.x1 >= b.x0 && a.y0 <= b.y1 && a.y1 >= b.y0
}

function insideRing(ring: readonly Local[], x: number, y: number): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

function distanceToSegment(x: number, y: number, a: Local, b: Local): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const len2 = dx * dx + dy * dy
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / len2))
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy))
}

/** How far from its quay line a click still selects a berth, metres. */
export const BERTH_REACH_M = 6

/** Whether a local point hits the shape: inside a polygon (holes count), or within `reachM` of a line or edge. */
export function hits(shape: MapShape, x: number, y: number, reachM: number): boolean {
  // A terminal boundary encloses everything else: only its edge is clickable
  const edgeOnly = !shape.closed || shape.rank === RANK.terminal
  // A line is hit within its half width or the reach, whichever is more; a berth within 6 m of its quay line
  const reach = !edgeOnly
    ? reachM
    : Math.max(reachM, (shape.lineWidthM ?? 0) / 2, shape.typeCode === 'BERTH' ? BERTH_REACH_M : 0)
  const { box } = shape
  if (x < box.x0 - reach || x > box.x1 + reach || y < box.y0 - reach || y > box.y1 + reach) {
    return false
  }
  if (!edgeOnly) {
    // even-odd across all rings: a point inside a hole is outside the shape
    return shape.rings.filter((ring) => insideRing(ring, x, y)).length % 2 === 1
  }
  return shape.rings.some((ring) =>
    ring.some((p, i) => {
      const prev = i > 0 ? ring[i - 1] : shape.closed ? ring[ring.length - 1] : null
      return prev !== null && distanceToSegment(x, y, prev, p) <= reach
    }),
  )
}

/** The topmost shape under a local point: assets from the highest draw order down, then background shapes. */
export function pick(
  assets: readonly MapShape[],
  background: readonly MapShape[],
  x: number,
  y: number,
  reachM: number,
): MapShape | null {
  for (let i = assets.length - 1; i >= 0; i--) {
    if (hits(assets[i], x, y, reachM)) return assets[i]
  }
  for (const shape of background) {
    if (shape.closed && hits(shape, x, y, 0)) return shape
  }
  return null
}

/** Chooses where the first view goes: the middle of the conventional terminal. */
export function conventionalTerminalCentre(shapes: readonly MapShape[]): [number, number] | null {
  const terminals = shapes.filter((s) => s.typeCode === 'TERMINAL_BOUNDARY')
  const shape = terminals.find((s) => /conventional/i.test(s.name)) ?? terminals[0]
  return shape ? [(shape.box.x0 + shape.box.x1) / 2, (shape.box.y0 + shape.box.y1) / 2] : null
}
