import { beforeAll, describe, expect, it } from 'vitest'
import { setActiveFrame, toLonLat, type Local } from '@/lib/geo'
import type { AssetType } from '@/lib/api'
import { assetTypesFixture } from '@/test/fixtures'
import { formatMessage } from '@/lib/validation/rules'
import { headingOf, placementBody, planPlacement, rotated, PLACED_NOTE } from './placement-plan'
import { placementProblem, type DraftShape, type PlacementSite } from './placement-rules'

const noSize = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }
const base = assetTypesFixture[0] as unknown as AssetType

const type = (
  code: string,
  over: Partial<Omit<AssetType, 'flags'>> & { flags?: Partial<AssetType['flags']> } = {},
): AssetType => ({
  ...base,
  code,
  label: code,
  ...over,
  flags: { ...base.flags, ...over.flags },
})

const HALL = type('WAREHOUSE', {
  geometryKind: 'RECT',
  flags: { solid: true },
  defaults: { ...noSize, lengthM: 80, widthM: 40, heightM: 12 },
})
const CRANE = type('QUAY_CRANE_RAIL', {
  flags: { mustBeOnBerth: true },
  defaults: { ...noSize, lengthM: 24, widthM: 16, heightM: 52 },
})
const BERTH = type('BERTH', {
  geometryKind: 'QUAY_SEGMENT',
  flags: { mayExtendOverWater: true },
  defaults: { ...noSize, lengthM: 200 },
})
const ZONE = type('EXCLUSION_ZONE', {
  geometryKind: 'POLYGON',
  placementMode: 'DRAW',
  flags: { zone: true },
  defaults: noSize,
})
const FORKLIFT = type('FORKLIFT', { defaults: { ...noSize, lengthM: 4, widthM: 2, heightM: 2.6 } })
const types = new Map([HALL, CRANE, BERTH, ZONE, FORKLIFT].map((t) => [t.code, t]))

const square = (x: number, y: number, half: number): Local[] => [
  [x - half, y - half],
  [x + half, y - half],
  [x + half, y + half],
  [x - half, y + half],
]

/** Local metres: land is y >= 0, the quay edge is the line y = 0 and the sea is below it. */
const site: PlacementSite = {
  extent: square(0, 0, 2000),
  land: [
    [-1000, 0],
    [1000, 0],
    [1000, 1000],
    [-1000, 1000],
  ],
  quay: [
    [-1000, 0],
    [1000, 0],
  ],
}

const shape = (
  id: string,
  name: string,
  typeCode: string,
  over: Partial<DraftShape> = {},
): DraftShape => ({ id, name, typeCode, ring: null, line: null, allowedTypes: [], ...over })

const hallAt = (x: number, y: number, name = 'Warehouse 1') => ({
  name,
  type: HALL,
  ring: square(x, y, 20),
  line: null,
})

beforeAll(() => setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }))

describe('placement rules: every reason with the exact message', () => {
  it('accepts a clear spot', () => {
    expect(placementProblem(hallAt(0, 200), site, types, [])).toBeNull()
  })

  it('OUT_OF_PORT', () => {
    expect(placementProblem(hallAt(5000, 200), site, types, [])).toBe(
      'Warehouse 1 lies outside the port extent',
    )
  })

  it('ON_WATER', () => {
    expect(placementProblem(hallAt(0, -10), site, types, [])).toBe(
      'Extends past the quay edge into the water',
    )
  })

  it('OUTSIDE_LAND', () => {
    expect(placementProblem(hallAt(1500, 200), site, types, [])).toBe('Outside the land area')
  })

  it('lets a berth stand over the water', () => {
    const line: Local[] = [
      [0, 0],
      [100, 0],
    ]
    expect(
      placementProblem({ name: 'Berth 1', type: BERTH, ring: null, line }, site, types, []),
    ).toBeNull()
  })

  it('IN_EXCLUSION_ZONE, unless the zone allows the type or the asset has an override', () => {
    const zone = shape('z', 'Fire lane', 'EXCLUSION_ZONE', { ring: square(0, 200, 50) })
    expect(placementProblem(hallAt(0, 200), site, types, [zone])).toBe(
      formatMessage('IN_EXCLUSION_ZONE', 'default', { zone: 'Fire lane' }),
    )
    expect(
      placementProblem(hallAt(0, 200), site, types, [{ ...zone, allowedTypes: ['WAREHOUSE'] }]),
    ).toBeNull()
    expect(
      placementProblem({ ...hallAt(0, 200), overridden: true }, site, types, [zone]),
    ).toBeNull()
  })

  it('OVERLAP with a solid asset, not with one that is not solid', () => {
    const hall = shape('h', 'Warehouse 2', 'WAREHOUSE', { ring: square(10, 200, 20) })
    expect(placementProblem(hallAt(0, 200), site, types, [hall])).toBe('Overlaps Warehouse 2')
    const forklift = shape('f', 'Forklift 1', 'FORKLIFT', { ring: square(10, 200, 2) })
    expect(placementProblem(hallAt(0, 200), site, types, [forklift])).toBeNull()
  })

  it('a moved asset never meets itself', () => {
    const hall = shape('h', 'Warehouse 1', 'WAREHOUSE', { ring: square(0, 200, 20) })
    expect(placementProblem({ ...hallAt(0, 200), selfId: 'h' }, site, types, [hall])).toBeNull()
  })

  it('BERTH_OVERLAP along the quay', () => {
    const other = shape('b', 'Berth 1', 'BERTH', {
      line: [
        [0, 0],
        [200, 0],
      ],
    })
    const along = (from: number, to: number) => ({
      name: 'Berth 2',
      type: BERTH,
      ring: null,
      line: [
        [from, 0],
        [to, 0],
      ] as Local[],
    })
    expect(placementProblem(along(150, 350), site, types, [other])).toBe(
      'Overlaps Berth 1 along the quay',
    )
    expect(placementProblem(along(200, 400), site, types, [other])).toBeNull()
  })

  it('CRANE_OFF_BERTH for a rail crane away from every berth', () => {
    const berth = shape('b', 'Berth 1', 'BERTH', {
      line: [
        [0, 0],
        [200, 0],
      ],
    })
    const crane = (x: number, y: number) => ({
      name: 'Crane 1',
      type: CRANE,
      ring: square(x, y, 8),
      line: null,
    })
    const off = 'A rail-mounted quay crane must stand on a berth'
    expect(placementProblem(crane(100, 10), site, types, [berth])).toBeNull()
    expect(placementProblem(crane(100, 10), site, types, [])).toBe(off)
    expect(placementProblem(crane(500, 10), site, types, [berth])).toBe(off)
  })
})

describe('placement plans', () => {
  it('berths snap to the quay line and slide along it, kept inside the line', () => {
    const at = (x: number) => planPlacement(BERTH, [x, 90], 0, site, [], types)
    const mid = at(300)
    expect(mid.snapped).toBe(true)
    expect(mid.line![0]![1]).toBeCloseTo(0)
    expect(mid.line![0]![0]).toBeCloseTo(200)
    expect(mid.line!.at(-1)![0]).toBeCloseTo(400)
    // near the end the berth keeps its 200 m
    expect(at(990).line![0]![0]).toBeCloseTo(800)
    expect(at(990).line!.at(-1)![0]).toBeCloseTo(1000)
  })

  it('rail quay cranes snap to the crane rail beside the nearest berth', () => {
    const berth = shape('b', 'Berth 1', 'BERTH', {
      line: [
        [0, 0],
        [400, 0],
      ],
    })
    const plan = planPlacement(CRANE, [150, 60], 45, site, [berth], types)
    expect(plan.snapped).toBe(true)
    expect(plan.anchor[0]).toBeCloseTo(150)
    expect(plan.anchor[1]).toBeCloseTo(10)
    expect(plan.rotationDeg).toBeCloseTo(0)
    expect(plan.ring).toHaveLength(4)
  })

  it('a free asset follows the pointer and the rotation', () => {
    const plan = planPlacement(HALL, [10, 20], 30, site, [], types)
    expect(plan.snapped).toBe(false)
    expect(plan.anchor).toEqual([10, 20])
    expect(plan.rotationDeg).toBe(30)
    expect(plan.lengthM).toBe(80)
  })

  it('a draw type dropped on the map gets a 60 x 40 m polygon or an 80 m line', () => {
    const polygon = planPlacement(ZONE, [0, 200], 0, site, [], types)
    expect(polygon.ring).toHaveLength(4)
    expect(polygon.lengthM).toBe(60)
    expect(polygon.widthM).toBe(40)
    const conveyor = type('CONVEYOR', {
      geometryKind: 'LINE',
      placementMode: 'DRAW',
      defaults: { ...noSize, lineWidthM: 2.4 },
    })
    const line = planPlacement(conveyor, [0, 200], 0, site, [], types).line!
    expect(line).toHaveLength(2)
    expect(Math.hypot(line[1]![0] - line[0]![0], line[1]![1] - line[0]![1])).toBeCloseTo(80)
  })

  it('R adds 15 degrees and wraps into -180..180', () => {
    expect(rotated(0)).toBe(15)
    expect(rotated(165)).toBe(180)
    expect(rotated(180)).toBe(-165)
    expect(rotated(-15)).toBe(0)
  })

  it('the heading is from true north, 0 up to 360', () => {
    for (const rotation of [-180, -90, 0, 15, 90, 180]) {
      const h = headingOf(rotation)
      expect(h).toBeGreaterThanOrEqual(0)
      expect(h).toBeLessThan(360)
    }
  })

  it('the request carries the rounded anchor, heading, default size and the designer provenance', () => {
    const plan = planPlacement(FORKLIFT, [10.123456789, 20], 30, site, [], types)
    const body = placementBody(plan, 'Forklift 1')
    const [lon, lat] = toLonLat(10.123456789, 20)
    expect(body).toMatchObject({
      typeCode: 'FORKLIFT',
      name: 'Forklift 1',
      lengthM: 4,
      widthM: 2,
      heightM: 2.6,
      provenance: { source: 'OPERATOR', accuracy: 'APPROXIMATE', note: PLACED_NOTE },
    })
    expect(body.anchor).toEqual([Math.round(lon * 1e7) / 1e7, Math.round(lat * 1e7) / 1e7])
    expect(body.headingDeg).toBe(headingOf(30))
  })
})
