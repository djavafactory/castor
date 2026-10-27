import { BufferAttribute, BufferGeometry, ShapeUtils, Vector2 } from 'three'
import { palette } from '@/features/scene/palette'
import type { Local } from '@/lib/geo'
import { LAYERS, type FlatLayerKey } from './layer-heights'

/**
 * Flat layers (areas, roads, pads, zones) as merged geometry: one geometry, so one draw call, per layer and colour.
 * Every vertex of a layer sits at that layer's fixed height. Local metres in, scene metres (x, y, -y) out.
 */

export type FlatItem =
  | { kind: 'polygon'; layer: FlatLayerKey; colour: string; ring: Local[]; holes?: Local[][] }
  | { kind: 'ribbon'; layer: FlatLayerKey; colour: string; points: Local[]; width: number }
  | {
      kind: 'dashes'
      layer: FlatLayerKey
      colour: string
      points: Local[]
      width: number
      dash: number
      gap: number
    }
  | { kind: 'bars'; layer: FlatLayerKey; colour: string; bars: [Local, Local][]; width: number }

export interface FlatBatch {
  key: string
  layer: FlatLayerKey
  colour: string
  geometry: BufferGeometry
}

class Accumulator {
  readonly positions: number[] = []
  readonly index: number[] = []
  private readonly y: number
  constructor(y: number) {
    this.y = y
  }

  private vertex(p: Local): number {
    this.positions.push(p[0], this.y, -p[1])
    return this.positions.length / 3 - 1
  }

  /** A triangle that faces up whatever order it was given in. */
  triangle(a: Local, b: Local, c: Local) {
    const cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    const [ia, ib, ic] = [this.vertex(a), this.vertex(b), this.vertex(c)]
    if (cross >= 0) this.index.push(ia, ib, ic)
    else this.index.push(ia, ic, ib)
  }

  quad(a: Local, b: Local, c: Local, d: Local) {
    this.triangle(a, b, c)
    this.triangle(a, c, d)
  }

  geometry(): BufferGeometry {
    const g = new BufferGeometry()
    const count = this.positions.length / 3
    const normals = new Float32Array(count * 3)
    for (let i = 0; i < count; i++) normals[i * 3 + 1] = 1
    g.setAttribute('position', new BufferAttribute(new Float32Array(this.positions), 3))
    g.setAttribute('normal', new BufferAttribute(normals, 3))
    g.setIndex(this.index)
    g.computeBoundingSphere()
    return g
  }
}

function dropClosing(ring: readonly Local[]): Local[] {
  const n = ring.length
  return n > 1 && ring[0][0] === ring[n - 1][0] && ring[0][1] === ring[n - 1][1]
    ? ring.slice(0, -1)
    : [...ring]
}

function addPolygon(acc: Accumulator, ring: Local[], holes: Local[][] = []) {
  const contour = dropClosing(ring)
  if (contour.length < 3) return
  const cleanHoles = holes.map(dropClosing).filter((h) => h.length >= 3)
  const toV = (p: Local) => new Vector2(p[0], p[1])
  const faces = ShapeUtils.triangulateShape(
    contour.map(toV),
    cleanHoles.map((h) => h.map(toV)),
  )
  const all = [...contour, ...cleanHoles.flat()]
  for (const [a, b, c] of faces) acc.triangle(all[a], all[b], all[c])
}

const MITRE_LIMIT_COS = 0.4

/** Left and right edge of an open polyline of the given width, with mitred joins (the mitre length is limited). */
export function ribbonEdges(points: readonly Local[], width: number): [Local[], Local[]] {
  const left: Local[] = []
  const right: Local[] = []
  const half = width / 2
  const unit = (a: Local, b: Local): Local => {
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1
    return [(b[0] - a[0]) / len, (b[1] - a[1]) / len]
  }
  for (let i = 0; i < points.length; i++) {
    const prev = i > 0 ? unit(points[i - 1], points[i]) : unit(points[i], points[i + 1])
    const next = i < points.length - 1 ? unit(points[i], points[i + 1]) : prev
    const n1: Local = [-prev[1], prev[0]]
    const n2: Local = [-next[1], next[0]]
    let mx = n1[0] + n2[0]
    let my = n1[1] + n2[1]
    const ml = Math.hypot(mx, my)
    if (ml < 1e-9) {
      mx = n1[0]
      my = n1[1]
    } else {
      mx /= ml
      my /= ml
    }
    const cos = mx * n1[0] + my * n1[1]
    const k = half / Math.max(cos, MITRE_LIMIT_COS)
    const [px, py] = points[i]
    left.push([px + mx * k, py + my * k])
    right.push([px - mx * k, py - my * k])
  }
  return [left, right]
}

function addRibbon(acc: Accumulator, points: readonly Local[], width: number) {
  const pts = points.filter(
    (p, i) => i === 0 || p[0] !== points[i - 1][0] || p[1] !== points[i - 1][1],
  )
  if (pts.length < 2) return
  const [l, r] = ribbonEdges(pts, width)
  for (let i = 0; i < pts.length - 1; i++) acc.quad(l[i], l[i + 1], r[i + 1], r[i])
}

/** Splits a polyline into dashes of `dash` metres separated by `gap` metres. */
export function dashSegments(points: readonly Local[], dash: number, gap: number): Local[][] {
  const out: Local[][] = []
  let current: Local[] = points.length > 0 ? [points[0]] : []
  let inDash = true
  let left = dash
  for (let i = 0; i < points.length - 1; i++) {
    const [ax, ay] = points[i]
    const [bx, by] = points[i + 1]
    const len = Math.hypot(bx - ax, by - ay)
    let at = 0
    while (len - at > left) {
      at += left
      const p: Local = [ax + ((bx - ax) * at) / len, ay + ((by - ay) * at) / len]
      if (inDash) {
        current.push(p)
        out.push(current)
        current = []
      } else {
        current = [p]
      }
      inDash = !inDash
      left = inDash ? dash : gap
    }
    left -= len - at
    if (inDash) current.push([bx, by])
  }
  if (inDash && current.length > 1) out.push(current)
  return out
}

/** Builds the merged geometries, one per layer and colour. */
export function buildFlatBatches(items: readonly FlatItem[]): FlatBatch[] {
  const batches = new Map<string, { layer: FlatLayerKey; colour: string; acc: Accumulator }>()
  for (const item of items) {
    const key = `${item.layer}|${item.colour}`
    let batch = batches.get(key)
    if (!batch) {
      batch = { layer: item.layer, colour: item.colour, acc: new Accumulator(LAYERS[item.layer].y) }
      batches.set(key, batch)
    }
    switch (item.kind) {
      case 'polygon':
        addPolygon(batch.acc, item.ring, item.holes)
        break
      case 'ribbon':
        addRibbon(batch.acc, item.points, item.width)
        break
      case 'dashes':
        for (const d of dashSegments(item.points, item.dash, item.gap)) {
          addRibbon(batch.acc, d, item.width)
        }
        break
      case 'bars':
        for (const [a, b] of item.bars) addRibbon(batch.acc, [a, b], item.width)
        break
    }
  }
  return [...batches].map(([key, b]) => ({
    key,
    layer: b.layer,
    colour: b.colour,
    geometry: b.acc.geometry(),
  }))
}

// ---- Item factories: the looks the ticket fixes ----

const DASH = 3
const GAP = 4
const MARK_WIDTH = 0.3

/** A road segment: grey fill with a white dashed centre line. */
export function roadSegment(points: Local[], width: number): FlatItem[] {
  return [
    { kind: 'ribbon', layer: 'roadSegment', colour: palette.road, points, width },
    {
      kind: 'dashes',
      layer: 'marking',
      colour: palette.white,
      points,
      width: Math.min(MARK_WIDTH, width * 0.05),
      dash: DASH,
      gap: GAP,
    },
  ]
}

export function internalRoad(points: Local[], width: number): FlatItem[] {
  return [{ kind: 'ribbon', layer: 'internalRoad', colour: palette.internalRoad, points, width }]
}

/** Fire access: pink fill with red dashes. */
export function fireAccess(points: Local[], width: number): FlatItem[] {
  return [
    { kind: 'ribbon', layer: 'fireAccess', colour: palette.fireAccessFill, points, width },
    {
      kind: 'dashes',
      layer: 'markingTop',
      colour: palette.brick,
      points,
      width: Math.min(MARK_WIDTH, width * 0.05),
      dash: DASH,
      gap: GAP,
    },
  ]
}

/** A junction: a square pad with a white stop bar across each of its four arms. */
export function junction(centre: Local, size: number, rotation = 0): FlatItem[] {
  const [cx, cy] = centre
  const c = Math.cos(rotation)
  const s = Math.sin(rotation)
  const rot = (x: number, y: number): Local => [cx + x * c - y * s, cy + x * s + y * c]
  const h = size / 2
  const bar = h - 0.6
  const bars: [Local, Local][] = [
    [rot(-h * 0.8, bar), rot(h * 0.8, bar)],
    [rot(-h * 0.8, -bar), rot(h * 0.8, -bar)],
    [rot(bar, -h * 0.8), rot(bar, h * 0.8)],
    [rot(-bar, -h * 0.8), rot(-bar, h * 0.8)],
  ]
  return [
    {
      kind: 'polygon',
      layer: 'junction',
      colour: palette.road,
      ring: [rot(-h, -h), rot(h, -h), rot(h, h), rot(-h, h)],
    },
    { kind: 'bars', layer: 'markingTop', colour: palette.white, bars, width: 0.4 },
  ]
}

/** OSM roads: flat ribbons of their width in grey with mitred joins. */
export function osmRoad(points: Local[], width: number): FlatItem[] {
  return [{ kind: 'ribbon', layer: 'osmRoad', colour: palette.osmRoad, points, width }]
}

/** Terminal boundary: T1 and T6 fills with a thin outline. */
export function terminalBoundary(ring: Local[], terminal: 'T1' | 'T6'): FlatItem[] {
  const colour = terminal === 'T1' ? palette.terminalT1 : palette.terminalT6
  return [
    { kind: 'polygon', layer: 'terminalBoundary', colour, ring },
    {
      kind: 'ribbon',
      layer: 'marking',
      colour: palette.quayWall,
      points: [...ring, ring[0]],
      width: 0.6,
    },
  ]
}

/** Surface areas use the ref.surface colour of the catalogue. */
export function surfaceArea(ring: Local[], colour: string): FlatItem[] {
  return [{ kind: 'polygon', layer: 'surfaceArea', colour, ring }]
}
