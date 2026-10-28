import { beforeAll, describe, expect, it } from 'vitest'
import { subjectOf, type EditShape } from '@/features/design/edit/edit-model'
import type { RuleContext } from '@/features/design/edit/edit-rules'
import { setActiveFrame } from '@/lib/geo'
import type { AssetType, LayoutAsset } from '@/lib/api'
import {
  chainageOf,
  localBounds,
  proposePad,
  proposeShape,
  slideAlongQuay,
  withAnchor,
  withBerthLength,
  withHeading,
  withRadius,
  withSize,
  withVertex,
  withoutVertex,
} from './shape-edits'

const FRAME = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }

beforeAll(() => {
  setActiveFrame(FRAME)
})

const rect: EditShape = { kind: 'rect', anchor: [0, 0], headingDeg: 0, length: 20, width: 10 }
const berth: EditShape = { kind: 'berth', a: [0, 0], b: [100, 0] }

describe('single-field shape edits', () => {
  it('sizes and turns a rectangle without touching the rest', () => {
    expect(withSize(rect, { length: 30 })).toEqual({ ...rect, length: 30 })
    expect(withSize(rect, { width: 4 })).toEqual({ ...rect, width: 4 })
    expect(withHeading(rect, 45)).toEqual({ ...rect, headingDeg: 45 })
    expect(withRadius(rect, 3)).toBe(rect)
  })

  it('moves the end of a berth and keeps its start: the length is the one asked for', () => {
    const shape = withBerthLength(berth, 250)
    expect(shape.kind === 'berth' && shape.a).toEqual([0, 0])
    expect(shape.kind === 'berth' && shape.b[0]).toBeCloseTo(250, 9)
    expect(shape.kind === 'berth' && shape.b[1]).toBeCloseTo(0, 9)
  })

  it('reads the chainage of a berth as the local x of its start', () => {
    expect(chainageOf(slideAlongQuay(berth, 40))).toBe(40)
    expect(chainageOf(rect)).toBeNull()
  })

  it('slides a berth, a rail crane or a circle along the quay, and leaves other shapes alone', () => {
    expect(slideAlongQuay(berth, 15)).toEqual({ kind: 'berth', a: [15, 0], b: [115, 0] })
    expect(slideAlongQuay(rect, -5)).toEqual({ ...rect, anchor: [-5, 0] })
    const line: EditShape = {
      kind: 'line',
      points: [
        [0, 0],
        [5, 5],
      ],
    }
    expect(slideAlongQuay(line, 10)).toBe(line)
  })

  it('moves the anchor of a rectangle or a circle only', () => {
    expect(withAnchor(rect, [7, 8])).toEqual({ ...rect, anchor: [7, 8] })
    expect(withAnchor(berth, [7, 8])).toBe(berth)
  })
})

describe('vertex edits', () => {
  const polygon: EditShape = {
    kind: 'polygon',
    points: [
      [0, 0],
      [10, 0],
      [10, 10],
      [0, 10],
    ],
  }

  it('moves one vertex', () => {
    expect(withVertex(polygon, 2, [12, 12])).toEqual({
      kind: 'polygon',
      points: [
        [0, 0],
        [10, 0],
        [12, 12],
        [0, 10],
      ],
    })
  })

  it('removes a vertex while the outline keeps its minimum of three', () => {
    expect(withoutVertex(polygon, 0)).toMatchObject({ kind: 'polygon' })
    const triangle: EditShape = {
      kind: 'polygon',
      points: [
        [0, 0],
        [10, 0],
        [5, 8],
      ],
    }
    expect(withoutVertex(triangle, 0)).toBeNull()
  })

  it('measures the envelope of an outline in metres', () => {
    expect(
      localBounds([
        [
          [0, 0],
          [10, 0],
          [10, 4],
        ],
      ]),
    ).toEqual({ width: 10, height: 4 })
    expect(localBounds([])).toEqual({ width: 0, height: 0 })
  })
})

const types = new Map<string, AssetType>([
  [
    'STORAGE_PAD',
    {
      code: 'STORAGE_PAD',
      label: 'Storage pad',
      geometryKind: 'RECT',
      flags: {
        mobile: false,
        solid: true,
        flat: true,
        zone: false,
        mayExtendOverWater: false,
        mustBeOnBerth: false,
      },
      min: { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null },
      max: { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null },
      defaults: { lengthM: 60, widthM: 30, heightM: 5, radiusM: null, lineWidthM: null },
    } as unknown as AssetType,
  ],
])

const padType = types.get('STORAGE_PAD')!

/** A 20 x 18 m pad, 2 bays by 3 rows, at the port origin. */
function padAsset(): LayoutAsset {
  const [lon, lat] = [FRAME.lon0, FRAME.lat0]
  return {
    id: 'pad-1',
    typeCode: 'STORAGE_PAD',
    name: 'Pad 1',
    rowVersion: 1,
    anchor: [lon, lat],
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [lon, lat],
          [lon + 1e-4, lat],
          [lon + 1e-4, lat + 1e-4],
          [lon, lat],
        ],
      ],
    },
    lengthM: 20,
    widthM: 18,
    headingDeg: 0,
    details: { storagePad: { bays: 2, rows: 3, bayPitchM: 10, rowPitchM: 6, maxHeightM: 5 } },
  } as unknown as LayoutAsset
}

const ctx: RuleContext = {
  type: padType,
  extent: null,
  land: null,
  hasQuay: false,
  neighbours: [],
  types,
  overridden: false,
}

describe('proposals: the move rules decide, the PATCH stores', () => {
  it('builds the PATCH of a valid shape: geometry, anchor and size', () => {
    const subject = subjectOf(padAsset(), padType)!
    const proposal = proposeShape(subject, withSize(subject.shape, { length: 30 }), ctx)
    expect(proposal.ok).toBe(true)
    if (!proposal.ok) return
    expect(proposal.changes).toMatchObject({ lengthM: 30, widthM: 18 })
    expect(proposal.changes.geometry).toMatchObject({ type: 'Polygon' })
  })

  it('changes the bay count of a pad and keeps the bay pitch, so the footprint grows', () => {
    const subject = subjectOf(padAsset(), padType)!
    const proposal = proposePad(subject, ctx, { bays: 4 })
    expect(proposal.ok).toBe(true)
    if (!proposal.ok) return
    expect(proposal.shape).toMatchObject({ kind: 'rect', length: 40 })
    expect(proposal.changes.details).toMatchObject({
      storagePad: { bays: 4, rows: 3, bayPitchM: 10, rowPitchM: 6 },
    })
  })

  it('changes a pitch of a pad: the footprint follows the bays and rows', () => {
    const subject = subjectOf(padAsset(), padType)!
    const proposal = proposePad(subject, ctx, { rowPitchM: 7 })
    expect(proposal.ok).toBe(true)
    if (!proposal.ok) return
    expect(proposal.shape).toMatchObject({ kind: 'rect', width: 21 })
    expect(proposal.changes.details).toMatchObject({ storagePad: { rowPitchM: 7, bayPitchM: 10 } })
  })

  it('refuses a shape the rules refuse, with the reason and no PATCH', () => {
    const capped = { ...padType, max: { ...padType.max, lengthM: 500 } } as AssetType
    const subject = subjectOf(padAsset(), capped)!
    const refused = proposeShape(subject, withSize(subject.shape, { length: 1000 }), {
      ...ctx,
      type: capped,
    })
    expect(refused).toEqual({ ok: false, reason: 'Length: enter a number of at most 500' })
  })
})
