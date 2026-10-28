import { beforeAll, describe, expect, it } from 'vitest'
import { assetTypeSchema, type AssetType, type LayoutAsset } from '@/lib/api'
import { headingToRotation, rectangleLocal, setActiveFrame, type Local } from '@/lib/geo'
import { assetTypesFixture } from '@/test/fixtures'
import { ringsOfShape, storedLonLat, subjectOf, type EditSubject } from './edit-model'
import { notChanged } from './edit-rules'
import {
  gizmoLabel,
  gizmoParts,
  heightOf,
  heightPatch,
  heightProblem,
  movesOnPress,
  resizeAlong,
  resizeHeight,
  rotateBy,
  shapeBox,
} from './gizmo-ops'

const BASE = assetTypesFixture[0]!
const NO_LIMITS = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }
const type = (over: Record<string, unknown>): AssetType =>
  assetTypeSchema.parse({
    ...BASE,
    ...over,
    flags: { ...BASE.flags, ...((over.flags as object) ?? {}) },
    defaults: { ...NO_LIMITS, ...((over.defaults as object) ?? {}) },
    min: { ...NO_LIMITS, ...((over.min as object) ?? {}) },
    max: { ...NO_LIMITS, ...((over.max as object) ?? {}) },
  })

const SHED = type({ code: 'SHED', geometryKind: 'RECT', max: { heightM: 30 } })
const PAD = type({ code: 'STORAGE_PAD', geometryKind: 'RECT' })
const TANK = type({ code: 'TANK', geometryKind: 'CIRCLE' })
const BERTH = type({ code: 'BERTH', geometryKind: 'QUAY_SEGMENT', min: { lengthM: 20 } })
const CRANE = type({
  code: 'QUAY_CRANE_RAIL',
  geometryKind: 'POINT',
  flags: { mustBeOnBerth: true },
})
const YARD = type({ code: 'YARD', geometryKind: 'POLYGON' })
const FENCE = type({ code: 'FENCE', geometryKind: 'LINE' })

beforeAll(() => {
  setActiveFrame({ lon0: 112.7, lat0: -7.2, rotDeg: 17.3 })
})

const lonLatOf = (p: Local) => [...storedLonLat(p)]
const ring = (points: Local[]) => [...points, points[0]!].map((p) => lonLatOf(p))
const rad = (deg: number) => (deg * Math.PI) / 180

function rect(code = 'SHED', over: Record<string, unknown> = {}): LayoutAsset {
  const anchor: Local = [100, 40]
  return {
    id: 'a1',
    typeCode: code,
    rowVersion: 3,
    name: 'Shed 1',
    anchor: lonLatOf(anchor),
    headingDeg: 33,
    lengthM: 50,
    widthM: 20,
    heightM: 12,
    geometry: {
      type: 'Polygon',
      coordinates: [ring(rectangleLocal(anchor, headingToRotation(33), 50, 20))],
    },
    ...over,
  }
}

function subject(asset: LayoutAsset, t: AssetType): EditSubject {
  const s = subjectOf(asset, t)
  if (!s) throw new Error('not editable')
  return s
}

const yard = (headingDeg?: number): LayoutAsset => ({
  id: 'y',
  typeCode: 'YARD',
  rowVersion: 1,
  name: 'Yard',
  ...(headingDeg === undefined ? {} : { headingDeg }),
  geometry: {
    type: 'Polygon',
    coordinates: [
      ring([
        [0, 10],
        [40, 10],
        [40, 30],
        [0, 30],
      ]),
    ],
  },
})

describe('resizing along an own axis', () => {
  it('keeps the opposite edge of a rectangle where it was', () => {
    const s = subject(rect(), SHED)
    const before = ringsOfShape(s.shape).rings[0]!
    const next = resizeAlong(s, 'length', 10)
    expect(next).toMatchObject({ length: 60, width: 20, headingDeg: 33 })
    const after = ringsOfShape(next).rings[0]!
    // corners 0 and 3 are the back edge (-length)
    for (const i of [0, 3]) {
      expect(after[i]![0]).toBeCloseTo(before[i]![0], 6)
      expect(after[i]![1]).toBeCloseTo(before[i]![1], 6)
    }
    const wider = ringsOfShape(resizeAlong(s, 'width', 5)).rings[0]!
    for (const i of [0, 1]) {
      expect(wider[i]![0]).toBeCloseTo(before[i]![0], 6)
      expect(wider[i]![1]).toBeCloseTo(before[i]![1], 6)
    }
  })

  it('stops at the minimum of the type, 2 m by default', () => {
    expect(resizeAlong(subject(rect(), SHED), 'length', -100)).toMatchObject({ length: 2 })
    const strict = type({ code: 'SHED', geometryKind: 'RECT', min: { widthM: 7 } })
    expect(resizeAlong(subject(rect(), strict), 'width', -100)).toMatchObject({ width: 7 })
  })

  it('keeps a storage pad at bays x pitch', () => {
    const pad = rect('STORAGE_PAD', {
      details: { storagePad: { bays: 3, rows: 2, bayPitchM: 16.67, rowPitchM: 10, maxHeightM: 5 } },
    })
    const next = resizeAlong(subject(pad, PAD), 'length', 10.004)
    if (next.kind !== 'rect') throw new Error('rect')
    expect(next.length).toBeCloseTo(3 * 20, 6)
  })

  it('changes the radius of a circle, never below 1 m', () => {
    const tank: LayoutAsset = {
      id: 't',
      typeCode: 'TANK',
      rowVersion: 1,
      name: 'Tank',
      anchor: lonLatOf([10, 50]),
      radiusM: 8,
      geometry: { type: 'Polygon', coordinates: [] },
    }
    const s = subject(tank, TANK)
    expect(resizeAlong(s, 'length', 2.5)).toMatchObject({ radius: 10.5 })
    expect(resizeAlong(s, 'length', -50)).toMatchObject({ radius: 1 })
    expect(gizmoParts(s)).toEqual(['length', 'height'])
  })

  it('scales a polygon along its own heading axis, not the global axes', () => {
    // A heading of 90 is local x in this frame only when the frame says so: use the stored heading's own axes
    const heading = 40
    const s = subject(yard(heading), YARD)
    const box = shapeBox(s, s.shape)
    expect(box.rotationDeg).toBeCloseTo(headingToRotation(heading), 6)
    const next = resizeAlong(s, 'length', 10)
    const after = shapeBox(s, next)
    expect(after.length).toBeCloseTo(box.length + 10, 1)
    expect(after.width).toBeCloseTo(box.width, 1)
    // The back edge in the own frame stays
    const a = rad(box.rotationDeg)
    const back = (b: typeof box) =>
      (b.centre[0] - (b.length / 2) * Math.cos(a)) * Math.cos(a) +
      (b.centre[1] - (b.length / 2) * Math.sin(a)) * Math.sin(a)
    expect(back(after)).toBeCloseTo(back(box), 1)
  })

  it('uses the quay frame for a polygon without a heading', () => {
    const s = subject(yard(), YARD)
    const next = resizeAlong(s, 'width', 20)
    if (next.kind !== 'polygon') throw new Error('polygon')
    // The near edge keeps its stored points, the far edge moves 20 m across
    expect(next.points.slice(0, 2)).toEqual(
      s.shape.kind === 'polygon' && s.shape.points.slice(0, 2),
    )
    expect(next.points[2]![1]).toBeCloseTo(50, 1)
    expect(next.points[3]![1]).toBeCloseTo(50, 1)
  })

  it('offers no arrow across a straight line', () => {
    const fence: LayoutAsset = {
      id: 'f',
      typeCode: 'FENCE',
      rowVersion: 1,
      name: 'Fence',
      geometry: { type: 'LineString', coordinates: [lonLatOf([0, 5]), lonLatOf([30, 5])] },
    }
    const s = subject(fence, FENCE)
    expect(gizmoParts(s)).toEqual(['length', 'height'])
    expect(resizeAlong(s, 'width', 10)).toEqual(s.shape)
    const longer = resizeAlong(s, 'length', 30)
    if (longer.kind !== 'line' || s.shape.kind !== 'line') throw new Error('line')
    expect(longer.points[0]).toEqual(s.shape.points[0])
    expect(longer.points[1]![0]).toBeCloseTo(60, 1)
  })

  it('makes a berth longer or shorter only, 20 m at least, and it never moves on a press', () => {
    const berth: LayoutAsset = {
      id: 'b',
      typeCode: 'BERTH',
      rowVersion: 1,
      name: 'Berth 1',
      anchor: lonLatOf([100, 0]),
      lengthM: 100,
      geometry: { type: 'LineString', coordinates: [lonLatOf([50, 0]), lonLatOf([150, 0])] },
    }
    const s = subject(berth, BERTH)
    expect(gizmoParts(s)).toEqual(['length'])
    expect(movesOnPress(s)).toBe(false)
    const next = resizeAlong(s, 'length', 25)
    if (next.kind !== 'berth') throw new Error('berth')
    expect(next.a[0]).toBeCloseTo(50, 2)
    expect(next.b[0]).toBeCloseTo(175, 1)
    const short = resizeAlong(s, 'length', -500)
    if (short.kind !== 'berth') throw new Error('berth')
    expect(short.b[0] - short.a[0]).toBeCloseTo(20, 2)
    expect(resizeAlong(s, 'width', 25)).toEqual(s.shape)
  })

  it('gives a rail crane no ring and no move on a press', () => {
    const s = subject(rect('QUAY_CRANE_RAIL'), CRANE)
    expect(gizmoParts(s)).toEqual(['length', 'width', 'height'])
    expect(movesOnPress(s)).toBe(false)
    expect(gizmoParts(subject(rect(), SHED))).toEqual(['length', 'width', 'height', 'rotate'])
    expect(movesOnPress(subject(rect(), SHED))).toBe(true)
  })
})

describe('height', () => {
  it('edits heightM, 0.5 m at least, up to the type maximum', () => {
    const s = subject(rect(), SHED)
    const spec = heightOf(s, SHED)
    expect(spec).toEqual({ value: 12, min: 0.5, max: 30, pad: false })
    expect(resizeHeight(spec, -40)).toBe(0.5)
    expect(resizeHeight(spec, 3.333)).toBe(15.33)
    expect(heightProblem(spec, 15)).toBeNull()
    expect(heightProblem(spec, 31)).toMatch(/30/)
    expect(heightPatch(s, spec, 15)).toEqual({ heightM: 15 })
  })

  it('edits the max height of a storage pad', () => {
    const details = {
      storagePad: { bays: 3, rows: 2, bayPitchM: 10, rowPitchM: 10, maxHeightM: 5 },
    }
    const s = subject(rect('STORAGE_PAD', { details }), PAD)
    const spec = heightOf(s, PAD)
    expect(spec).toMatchObject({ value: 5, min: 1, pad: true })
    expect(heightPatch(s, spec, 7)).toEqual({
      details: { storagePad: { ...details.storagePad, maxHeightM: 7 } },
    })
    expect(heightProblem(spec, 7)).toBeNull()
  })
})

describe('rotation', () => {
  it('adds the swept angle in whole degrees and snaps to 15 with Shift', () => {
    const s = subject(rect(), SHED)
    // Counter-clockwise is a smaller true heading
    expect(rotateBy(s.shape, 10.4, false)).toMatchObject({ headingDeg: 23 })
    expect(rotateBy(s.shape, -20, true)).toMatchObject({ headingDeg: 60 })
    expect(rotateBy(s.shape, 40, false)).toMatchObject({ headingDeg: 353 })
  })
})

describe('labels', () => {
  it('names each gesture for the history and the refusal toast', () => {
    expect(gizmoLabel('length', 'Shed 1')).toBe('Changed the length of Shed 1')
    expect(gizmoLabel('width', 'Shed 1')).toBe('Changed the width of Shed 1')
    expect(gizmoLabel('height', 'Shed 1')).toBe('Changed the height of Shed 1')
    expect(gizmoLabel('rotate', 'Shed 1')).toBe('Rotated Shed 1')
    expect(gizmoLabel('move', 'Shed 1')).toBe('Moved Shed 1')
    expect(notChanged('too big')).toBe('Not changed: too big')
  })
})
