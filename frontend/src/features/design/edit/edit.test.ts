import { beforeAll, describe, expect, it } from 'vitest'
import { assetTypeSchema, type AssetType, type LayoutAsset } from '@/lib/api'
import {
  fromLonLat,
  headingToRotation,
  rectangleLocal,
  setActiveFrame,
  type Local,
} from '@/lib/geo'
import { assetTypesFixture } from '@/test/fixtures'
import {
  inverseOf,
  patchOf,
  ringsOfShape,
  storedLonLat,
  subjectOf,
  type EditShape,
  type EditSubject,
} from './edit-model'
import {
  HANDLE_HIT_PX,
  KNOB_OFFSET_PX,
  editableHandles,
  handleAt,
  insertVertex,
  moveBerthEnd,
  moveShape,
  moveVertex,
  removeVertex,
  resizeCircle,
  resizeRect,
  rotateRect,
} from './edit-ops'
import { checkEdit, notMoved, type Neighbour, type RuleContext } from './edit-rules'

const BASE = assetTypesFixture[0]!
const NO_LIMITS = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }
const type = (over: Record<string, unknown>): AssetType =>
  assetTypeSchema.parse({
    ...BASE,
    ...over,
    flags: { ...BASE.flags, ...((over.flags as object) ?? {}) },
    min: { ...NO_LIMITS, ...((over.min as object) ?? {}) },
    max: { ...NO_LIMITS, ...((over.max as object) ?? {}) },
  })

const SHED = type({ code: 'SHED', geometryKind: 'RECT', flags: { solid: true } })
const PAD = type({ code: 'STORAGE_PAD', geometryKind: 'RECT', flags: { solid: true } })
const TANK = type({ code: 'TANK', geometryKind: 'CIRCLE', flags: { solid: true } })
const BERTH = type({
  code: 'BERTH',
  geometryKind: 'QUAY_SEGMENT',
  flags: { mayExtendOverWater: true },
  min: { lengthM: 20 },
})
const CRANE = type({
  code: 'QUAY_CRANE_RAIL',
  geometryKind: 'POINT',
  flags: { mustBeOnBerth: true },
})
const YARD = type({ code: 'YARD', geometryKind: 'POLYGON', flags: { solid: true } })
const FENCE = type({ code: 'FENCE', geometryKind: 'LINE' })

beforeAll(() => {
  setActiveFrame({ lon0: 112.7, lat0: -7.2, rotDeg: 17.3 })
})

const lonLatOf = (p: Local) => [...storedLonLat(p)]
const ring = (points: Local[]) => [...points, points[0]!].map((p) => lonLatOf(p))
const rad = (deg: number) => (deg * Math.PI) / 180

function rectAsset(over: Record<string, unknown> = {}): LayoutAsset {
  const anchor: Local = [100, 40]
  const corners = rectangleLocal(anchor, headingToRotation(33), 50, 20)
  return {
    id: 'a1',
    typeCode: 'SHED',
    rowVersion: 3,
    name: 'Shed 1',
    anchor: lonLatOf(anchor),
    headingDeg: 33,
    lengthM: 50,
    widthM: 20,
    geometry: { type: 'Polygon', coordinates: [ring(corners)] },
    ...over,
  }
}

const berthAsset = (): LayoutAsset => ({
  id: 'b',
  typeCode: 'BERTH',
  rowVersion: 1,
  name: 'Berth 1',
  anchor: lonLatOf([100, 0]),
  lengthM: 100,
  geometry: { type: 'LineString', coordinates: [lonLatOf([50, 0]), lonLatOf([150, 0])] },
})

const tankAsset = (): LayoutAsset => ({
  id: 'c',
  typeCode: 'TANK',
  rowVersion: 1,
  name: 'Tank',
  anchor: lonLatOf([10, 50]),
  radiusM: 8,
  geometry: { type: 'Polygon', coordinates: [] },
})

const yardAsset = (): LayoutAsset => ({
  id: 'y',
  typeCode: 'YARD',
  rowVersion: 1,
  name: 'Yard',
  geometry: {
    type: 'Polygon',
    coordinates: [
      ring([
        [0, 10],
        [40, 10],
        [40, 50],
        [0, 50],
      ]),
    ],
  },
})

function subject(asset: LayoutAsset, t: AssetType): EditSubject {
  const s = subjectOf(asset, t)
  if (!s) throw new Error('not editable')
  return s
}

const first = (shape: EditShape) => ringsOfShape(shape).rings[0]!

describe('resizing a rectangle by a corner', () => {
  it('keeps the opposite corner fixed and works in the asset own frame', () => {
    const s = subject(rectAsset(), SHED)
    const before = first(s.shape)
    // grab corner 2 (+length, +width) and pull it 10 m further along the length and 5 m across
    const a = rad(headingToRotation(33))
    const pointer: Local = [
      before[2]![0] + 10 * Math.cos(a) - 5 * Math.sin(a),
      before[2]![1] + 10 * Math.sin(a) + 5 * Math.cos(a),
    ]
    const next = resizeRect(s, 2, pointer)
    expect(next).toMatchObject({ kind: 'rect', length: 60, width: 25, headingDeg: 33 })
    const moved = first(next)[0]!
    expect(moved[0]).toBeCloseTo(before[0]![0], 3)
    expect(moved[1]).toBeCloseTo(before[0]![1], 3)
  })

  it('never goes below the minimum of the type, 2 m when the type has none', () => {
    const s = subject(rectAsset(), SHED)
    const fixed = first(s.shape)[0]!
    expect(resizeRect(s, 2, fixed)).toMatchObject({ length: 2, width: 2 })
    const strict = subject(
      rectAsset(),
      type({ code: 'SHED', geometryKind: 'RECT', min: { lengthM: 12, widthM: 7 } }),
    )
    expect(resizeRect(strict, 2, fixed)).toMatchObject({ length: 12, width: 7 })
  })

  it('rounds sizes to 0.01 m', () => {
    const s = subject(rectAsset(), SHED)
    const corner = first(s.shape)[2]!
    const a = rad(headingToRotation(33))
    const next = resizeRect(s, 2, [
      corner[0] + 3.14159 * Math.cos(a),
      corner[1] + 3.14159 * Math.sin(a),
    ])
    expect(next).toMatchObject({ length: 53.14 })
  })

  it('changes the bay and row pitch of a storage pad, so its size stays bays x pitch', () => {
    const s = subject(
      rectAsset({
        typeCode: 'STORAGE_PAD',
        details: { storagePad: { bays: 2, rows: 1, bayPitchM: 25, rowPitchM: 20 } },
      }),
      PAD,
    )
    const corner = first(s.shape)[2]!
    const a = rad(headingToRotation(33))
    const next = resizeRect(s, 2, [corner[0] + 10 * Math.cos(a), corner[1] + 10 * Math.sin(a)])
    expect(next).toMatchObject({ length: 60, width: 20 })
    const patch = patchOf(s, next) as { details: { storagePad: Record<string, number> } }
    expect(patch.details.storagePad).toMatchObject({
      bays: 2,
      rows: 1,
      bayPitchM: 30,
      rowPitchM: 20,
    })
  })
})

describe('rotating', () => {
  const pointerAt = (heading: number): Local => {
    // the front edge points where the pointer is
    const rotation = rad(90 - heading - 17.3)
    return [100 + 30 * Math.cos(rotation), 40 + 30 * Math.sin(rotation)]
  }

  it('sets a whole-degree true heading from the pointer', () => {
    const s = subject(rectAsset(), SHED)
    expect(rotateRect(s.shape, pointerAt(90), false)).toMatchObject({ headingDeg: 90 })
    expect(rotateRect(s.shape, pointerAt(52.4), false)).toMatchObject({ headingDeg: 52 })
  })

  it('snaps to 15 degrees with Shift', () => {
    const s = subject(rectAsset(), SHED)
    expect(rotateRect(s.shape, pointerAt(52), true)).toMatchObject({ headingDeg: 45 })
  })

  it('keeps the heading in 0 to 359', () => {
    const s = subject(rectAsset(), SHED)
    expect(rotateRect(s.shape, pointerAt(359.8), false)).toMatchObject({ headingDeg: 0 })
  })
})

describe('circles, berths and moving', () => {
  it('a circle radius is at least 1 m', () => {
    const s = subject(tankAsset(), TANK)
    expect(resizeCircle(s, [10.2, 50])).toMatchObject({ radius: 1 })
    expect(resizeCircle(s, [20, 50])).toMatchObject({ radius: 10 })
  })

  it('drags a berth end along the quay line and keeps it at least 20 m long', () => {
    const s = subject(berthAsset(), BERTH)
    const longer = moveBerthEnd(s, 1, [180.004, 9]) as { b: Local }
    expect(longer.b[0]).toBeCloseTo(180, 1)
    expect(longer.b[1]).toBeCloseTo((s.shape as { b: Local }).b[1], 9)
    const shorter = moveBerthEnd(s, 1, [55, 0]) as { b: Local }
    expect(shorter.b[0]).toBeCloseTo(70, 1)
    const left = moveBerthEnd(s, 0, [140, 0]) as { a: Local }
    expect(left.a[0]).toBeCloseTo(130, 1)
  })

  it('moves a berth along the quay only', () => {
    const s = subject(berthAsset(), BERTH)
    const next = moveShape(s, s.shape, 12.345, 9) as { a: Local }
    expect(next.a[0] - (s.shape as { a: Local }).a[0]).toBeCloseTo(12.35, 9)
    expect(next.a[1]).toBeCloseTo((s.shape as { a: Local }).a[1], 9)
  })

  it('moves a rail crane along the quay only, anything else freely, in 0.01 m steps', () => {
    const crane = subject(rectAsset({ typeCode: 'QUAY_CRANE_RAIL', id: 'cr' }), CRANE)
    expect(crane.alongQuay).toBe(true)
    const slid = moveShape(crane, crane.shape, 5, 7) as { anchor: Local }
    const from = (crane.shape as { anchor: Local }).anchor
    expect(slid.anchor[0] - from[0]).toBeCloseTo(5, 9)
    expect(slid.anchor[1]).toBe(from[1])
    const shed = subject(rectAsset(), SHED)
    const free = moveShape(shed, shed.shape, 1.006, -2.004) as { anchor: Local }
    expect(free.anchor[0] - (shed.shape as { anchor: Local }).anchor[0]).toBeCloseTo(1.01, 9)
    expect(free.anchor[1] - (shed.shape as { anchor: Local }).anchor[1]).toBeCloseTo(-2, 9)
  })
})

describe('vertices', () => {
  it('a midpoint drag inserts a vertex on that edge', () => {
    const s = subject(yardAsset(), YARD)
    const next = insertVertex(s.shape, 0) as { points: Local[] }
    expect(next.points).toHaveLength(5)
    expect(next.points[1]![0]).toBeCloseTo(20, 2)
    expect(next.points[1]![1]).toBeCloseTo(10, 2)
  })

  it('removes a vertex only while more than 3 (polygon) or 2 (line) points remain', () => {
    const s = subject(yardAsset(), YARD)
    const three = removeVertex(s.shape, 1)
    expect(three).not.toBeNull()
    expect(removeVertex(three!, 0)).toBeNull()
    const line = subject(
      {
        id: 'f',
        typeCode: 'FENCE',
        rowVersion: 1,
        name: 'Fence',
        geometry: { type: 'LineString', coordinates: [lonLatOf([0, 10]), lonLatOf([30, 10])] },
      },
      FENCE,
    )
    expect(removeVertex(line.shape, 0)).toBeNull()
  })

  it('a vertex that was not touched keeps its stored coordinate exactly', () => {
    const s = subject(yardAsset(), YARD)
    const moved = moveVertex(s.shape, 2, [45.123, 55.456])
    const geometry = (patchOf(s, moved) as { geometry: { coordinates: number[][][] } }).geometry
    const stored = (s.asset.geometry as { coordinates: number[][][] }).coordinates[0]!
    expect(geometry.coordinates[0]![0]).toEqual(stored[0])
    expect(geometry.coordinates[0]![1]).toEqual(stored[1])
    expect(geometry.coordinates[0]![2]).not.toEqual(stored[2])
    // the ring stays closed
    expect(geometry.coordinates[0]!.at(-1)).toEqual(geometry.coordinates[0]![0])
  })
})

describe('handles', () => {
  const view = { x: 100, y: 40, k: 2 }
  const size = { width: 800, height: 600 }

  it('a rectangle has four corner handles and a knob 28 px beyond the front edge', () => {
    const s = subject(rectAsset(), SHED)
    const handles = editableHandles(s, view.k)
    expect(handles.filter((h) => h.kind === 'corner')).toHaveLength(4)
    const knob = handles.find((h) => h.kind === 'rotate')!
    const gap = Math.hypot(knob.at[0] - knob.stemFrom![0], knob.at[1] - knob.stemFrom![1])
    expect(gap * view.k).toBeCloseTo(KNOB_OFFSET_PX, 6)
  })

  it('a rail crane has no knob, a circle one radius handle, a berth two end handles', () => {
    const crane = subject(rectAsset({ typeCode: 'QUAY_CRANE_RAIL', id: 'cr' }), CRANE)
    expect(editableHandles(crane, 1).some((h) => h.kind === 'rotate')).toBe(false)
    expect(editableHandles(subject(tankAsset(), TANK), 1).map((h) => h.kind)).toEqual(['radius'])
    expect(editableHandles(subject(berthAsset(), BERTH), 1).map((h) => h.kind)).toEqual([
      'end',
      'end',
    ])
  })

  it('a polygon has a handle on every vertex and a midpoint handle on every edge', () => {
    const handles = editableHandles(subject(yardAsset(), YARD), 1)
    expect(handles.filter((h) => h.kind === 'vertex')).toHaveLength(4)
    expect(handles.filter((h) => h.kind === 'mid')).toHaveLength(4)
  })

  it('finds the handle within reach of a pixel and ignores one beyond it', () => {
    const handles = editableHandles(subject(rectAsset(), SHED), view.k)
    const corner = handles[0]!
    const px = size.width / 2 + (corner.at[0] - view.x) * view.k
    const py = size.height / 2 - (corner.at[1] - view.y) * view.k
    expect(handleAt(handles, view, size, px + 2, py - 2)?.index).toBe(0)
    expect(handleAt(handles, view, size, px + HANDLE_HIT_PX + 3, py)).toBeNull()
  })
})

describe('no drift after save and reload (PRD 19.1)', () => {
  it('a resize in metres and a rotation to a true heading come back unchanged within 1 cm', () => {
    const s = subject(rectAsset(), SHED)
    const corner = first(s.shape)[2]!
    const a = rad(headingToRotation(33))
    const resized = resizeRect(s, 2, [
      corner[0] + 12.34 * Math.cos(a),
      corner[1] + 12.34 * Math.sin(a),
    ])
    const rotated = rotateRect(resized, [100, 80], false) as Extract<EditShape, { kind: 'rect' }>
    const saved: LayoutAsset = { ...s.asset, ...patchOf(s, rotated) }
    const reloaded = subject(saved, SHED).shape as Extract<EditShape, { kind: 'rect' }>

    expect(reloaded).toMatchObject({
      length: rotated.length,
      width: rotated.width,
      headingDeg: rotated.headingDeg,
    })
    const drift = Math.hypot(
      reloaded.anchor[0] - rotated.anchor[0],
      reloaded.anchor[1] - rotated.anchor[1],
    )
    expect(drift).toBeLessThanOrEqual(0.01)

    // the stored footprint equals the one rebuilt from anchor, heading and size
    const rebuilt = rectangleLocal(
      reloaded.anchor,
      headingToRotation(rotated.headingDeg),
      rotated.length,
      rotated.width,
    )
    const stored = (saved.geometry as { coordinates: number[][][] }).coordinates[0]!.slice(
      0,
      -1,
    ).map((p) => fromLonLat(p[0]!, p[1]!))
    stored.forEach((p, i) =>
      expect(Math.hypot(p[0] - rebuilt[i]![0], p[1] - rebuilt[i]![1])).toBeLessThanOrEqual(0.01),
    )
  })

  it('the inverse patch puts the old values back', () => {
    const s = subject(rectAsset(), SHED)
    const changes = patchOf(s, moveShape(s, s.shape, 5, 5))
    const back = { ...s.asset, ...changes, ...inverseOf(s.asset, changes) }
    expect(back.anchor).toEqual(s.asset.anchor)
    expect(back.geometry).toEqual(s.asset.geometry)
  })
})

describe('the rules', () => {
  const land: Local[] = [
    [-500, 0],
    [500, 0],
    [500, 400],
    [-500, 400],
  ]
  const extent: Local[] = [
    [-1000, -300],
    [1000, -300],
    [1000, 900],
    [-1000, 900],
  ]
  const types = new Map([SHED, PAD, TANK, BERTH, CRANE, YARD, FENCE].map((x) => [x.code, x]))
  const ctx = (t: AssetType, neighbours: Neighbour[] = []): RuleContext => ({
    type: t,
    extent,
    land,
    hasQuay: true,
    neighbours,
    types,
    overridden: false,
  })
  const shed = () => subject(rectAsset(), SHED)
  const neighbour = (over: Partial<Neighbour>): Neighbour => ({
    id: 'o',
    name: 'Shed 2',
    typeCode: 'SHED',
    rings: [rectangleLocal([400, 40], 0, 50, 20)],
    closed: true,
    allowedTypes: [],
    ...over,
  })

  it('accepts a clean move', () => {
    const s = shed()
    expect(checkEdit(s, moveShape(s, s.shape, 10, 0), ctx(SHED))).toEqual({ ok: true })
  })

  it('refuses a polygon whose outline would cross itself', () => {
    const s = subject(yardAsset(), YARD)
    const bow = moveVertex(moveVertex(s.shape, 2, [-10, 50]), 3, [50, 50])
    const verdict = checkEdit(s, bow, ctx(YARD))
    expect(verdict).toEqual({ ok: false, reason: 'the outline would cross itself' })
    expect(notMoved('the outline would cross itself')).toBe(
      'Not moved: the outline would cross itself',
    )
  })

  it('refuses a shape in the water, with the catalogue wording', () => {
    const s = shed()
    expect(checkEdit(s, moveShape(s, s.shape, 0, -200), ctx(SHED))).toEqual({
      ok: false,
      reason: 'Extends past the quay edge into the water',
    })
  })

  it('lets a type that may extend over water cross the quay line', () => {
    const over = type({ code: 'SHED', geometryKind: 'RECT', flags: { mayExtendOverWater: true } })
    const s = subject(rectAsset(), over)
    expect(checkEdit(s, moveShape(s, s.shape, 0, -50), { ...ctx(over), land: null })).toEqual({
      ok: true,
    })
  })

  it('refuses a shape outside the port extent', () => {
    const s = shed()
    expect(checkEdit(s, moveShape(s, s.shape, 5000, 0), ctx(SHED))).toEqual({
      ok: false,
      reason: 'Shed 1 lies outside the port extent',
    })
  })

  it('refuses a size above the maximum of the type, and a berth below 20 m', () => {
    const tight = type({ code: 'SHED', geometryKind: 'RECT', max: { lengthM: 55 } })
    const s = subject(rectAsset(), tight)
    const grown = { ...(s.shape as object), length: 60 } as EditShape
    expect(checkEdit(s, grown, ctx(tight))).toEqual({
      ok: false,
      reason: 'Length: enter a number of at most 55',
    })
    const b = subject(berthAsset(), BERTH)
    const stub: EditShape = { kind: 'berth', a: [50, 0], b: [60, 0] }
    expect(checkEdit(b, stub, ctx(BERTH))).toEqual({
      ok: false,
      reason: 'Length: enter a number of at least 20',
    })
  })

  it('refuses an overlap with another solid shape, but not shapes that only touch', () => {
    const s = shed()
    const onTop = neighbour({ rings: [rectangleLocal([100, 40], 0, 50, 20)] })
    expect(checkEdit(s, s.shape, ctx(SHED, [onTop]))).toEqual({
      ok: false,
      reason: 'Overlaps Shed 2',
    })
    expect(checkEdit(s, s.shape, ctx(SHED, [neighbour({})]))).toEqual({ ok: true })
    const flat: EditShape = {
      kind: 'rect',
      anchor: [100, 40],
      headingDeg: 90 - 17.3,
      length: 50,
      width: 20,
    }
    const beside = neighbour({ rings: [rectangleLocal([150, 40], 0, 50, 20)] })
    expect(checkEdit(s, { ...flat, headingDeg: 72.7 }, ctx(SHED, [beside]))).toEqual({ ok: true })
  })

  it('refuses a rail crane that no longer stands on a berth', () => {
    const crane = subject(rectAsset({ typeCode: 'QUAY_CRANE_RAIL', id: 'cr' }), CRANE)
    const berth = neighbour({
      id: 'b',
      name: 'Berth 1',
      typeCode: 'BERTH',
      rings: [
        [
          [0, 0],
          [150, 0],
        ],
      ],
      closed: false,
    })
    const at = (x: number, y: number): EditShape =>
      ({ ...(crane.shape as object), anchor: [x, y] }) as EditShape
    expect(checkEdit(crane, at(100, 26), ctx(CRANE, [berth]))).toEqual({ ok: true })
    expect(checkEdit(crane, at(300, 26), ctx(CRANE, [berth]))).toEqual({
      ok: false,
      reason: 'A rail-mounted quay crane must stand on a berth',
    })
  })

  it('refuses two berths that overlap along the quay', () => {
    const b = subject(berthAsset(), BERTH)
    const other = neighbour({
      id: 'b2',
      name: 'Berth 2',
      typeCode: 'BERTH',
      rings: [
        [
          [140, 0],
          [240, 0],
        ],
      ],
      closed: false,
    })
    expect(checkEdit(b, b.shape, ctx(BERTH, [other]))).toEqual({
      ok: false,
      reason: 'Overlaps Berth 2 along the quay',
    })
  })
})
