import { describe, expect, it } from 'vitest'
import { palette } from '@/features/scene/palette'
import type { AssetType } from '@/lib/api/schemas'
import { fromLonLat, setActiveFrame, toLonLat } from '@/lib/geo'
import {
  RANK,
  assetShapes,
  boxesTouch,
  boxOf,
  drawRank,
  hits,
  labelFits,
  osmShapes,
  pick,
  type MapAssetSource,
  type MapShape,
} from './map-scene'

const FALLBACK = palette.pad
const OUTLINE = palette.osmBuilding

// Set at once: the describe blocks below build their shapes while the file loads
setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })

function type(
  code: string,
  category: string,
  flat = false,
  colourHex: string = palette.ink,
): AssetType {
  return {
    code,
    label: code,
    paletteGroup: 'g',
    category,
    colourHex,
    flags: {
      mobile: false,
      solid: true,
      flat,
      zone: false,
      mayExtendOverWater: false,
      mustBeOnBerth: false,
    },
  } as unknown as AssetType
}

const TYPES: AssetType[] = [
  type('TERMINAL_BOUNDARY', 'ZONE'),
  type('SURFACE_AREA', 'ZONE', true),
  type('ROAD_SEGMENT', 'TRANSPORT', true),
  type('PAINTED_MARK', 'TRANSPORT', true),
  type('STORAGE_PAD', 'STORAGE'),
  type('WAREHOUSE', 'STORAGE'),
  type('SILO', 'DRY_BULK'),
  type('BERTH', 'MARINE'),
  type('QUAY_CRANE_RAIL', 'LIFTING'),
  type('GATE', 'TRANSPORT'),
]

/** A rectangle of `w` x `h` local metres with its corner at local (x, y), as GeoJSON in WGS84. */
function rect(x: number, y: number, w: number, h: number) {
  const ring = [
    [x, y],
    [x + w, y],
    [x + w, y + h],
    [x, y + h],
    [x, y],
  ].map(([px, py]) => toLonLat(px, py))
  return { type: 'Polygon', coordinates: [ring] }
}

function line(points: [number, number][]) {
  return { type: 'LineString', coordinates: points.map(([x, y]) => toLonLat(x, y)) }
}

function asset(
  id: string,
  typeCode: string,
  geometry: unknown,
  extra: object = {},
): MapAssetSource {
  return { id, typeCode, name: id, geometry, ...extra }
}

describe('draw order', () => {
  it('goes terminal boundaries, surface areas, roads, flat assets, pads, buildings, others, berths, cranes', () => {
    const rank = (code: string) =>
      drawRank(
        TYPES.find((t) => t.code === code),
        code,
      )
    expect([
      rank('TERMINAL_BOUNDARY'),
      rank('SURFACE_AREA'),
      rank('ROAD_SEGMENT'),
      rank('PAINTED_MARK'),
      rank('STORAGE_PAD'),
      rank('WAREHOUSE'),
      rank('SILO'),
      rank('BERTH'),
      rank('QUAY_CRANE_RAIL'),
    ]).toEqual([
      RANK.terminal,
      RANK.surface,
      RANK.road,
      RANK.flat,
      RANK.pad,
      RANK.building,
      RANK.other,
      RANK.berth,
      RANK.crane,
    ])
    expect(RANK.terminal).toBeLessThan(RANK.surface)
    expect(RANK.surface).toBeLessThan(RANK.road)
    expect(RANK.road).toBeLessThan(RANK.flat)
    expect(RANK.flat).toBeLessThan(RANK.pad)
    expect(RANK.pad).toBeLessThan(RANK.building)
    expect(RANK.building).toBeLessThan(RANK.other)
    expect(RANK.other).toBeLessThan(RANK.berth)
    expect(RANK.berth).toBeLessThan(RANK.crane)
  })

  it('draws the assets of a layout in that order whatever order they arrive in', () => {
    const shapes = assetShapes(
      [
        asset(
          'crane',
          'QUAY_CRANE_RAIL',
          line([
            [0, 0],
            [100, 0],
          ]),
        ),
        asset('silo', 'SILO', rect(0, 0, 20, 20)),
        asset('terminal', 'TERMINAL_BOUNDARY', rect(-50, -50, 500, 500)),
        asset(
          'berth',
          'BERTH',
          line([
            [0, 5],
            [100, 5],
          ]),
        ),
        asset('pad', 'STORAGE_PAD', rect(0, 0, 30, 30)),
      ],
      TYPES,
      FALLBACK,
    )
    expect(shapes.map((s) => s.id)).toEqual(['terminal', 'pad', 'silo', 'berth', 'crane'])
  })

  it('treats an unknown type as an ordinary asset in the fallback colour', () => {
    const [shape] = assetShapes([asset('x', 'MYSTERY', rect(0, 0, 10, 10))], TYPES, FALLBACK)
    expect(shape.rank).toBe(RANK.other)
    expect(shape.colour).toBe(FALLBACK)
  })
})

describe('shapes', () => {
  it('are in local metres of the quay frame', () => {
    const [shape] = assetShapes([asset('b', 'SILO', rect(100, 200, 40, 30))], TYPES, FALLBACK)
    expect(shape.box.x0).toBeCloseTo(100, 3)
    expect(shape.box.x1).toBeCloseTo(140, 3)
    expect(shape.box.y0).toBeCloseTo(200, 3)
    expect(shape.box.y1).toBeCloseTo(230, 3)
    expect(shape.minSideM).toBeCloseTo(30, 3)
  })

  it('leave out assets without a usable geometry', () => {
    expect(
      assetShapes(
        [
          asset('a', 'SILO', null),
          asset('b', 'SILO', { type: 'Point', coordinates: [101.3, 2.95] }),
          asset('c', 'SILO', { type: 'Polygon', coordinates: 'nope' }),
        ],
        TYPES,
        FALLBACK,
      ),
    ).toEqual([])
  })

  it('know the smaller side of a footprint by size, radius or box', () => {
    const [sized, round, bare] = assetShapes(
      [
        asset('s', 'SILO', rect(0, 0, 40, 30), { lengthM: 40, widthM: 12 }),
        asset('r', 'SILO', rect(0, 0, 40, 40), { radiusM: 15 }),
        asset('b', 'SILO', rect(0, 0, 50, 20)),
      ],
      TYPES,
      FALLBACK,
    )
    expect(sized.minSideM).toBe(12)
    expect(round.minSideM).toBe(30)
    expect(bare.minSideM).toBeCloseTo(20, 3)
  })

  it('give OSM outlines a rank below every asset', () => {
    const [shape] = osmShapes(
      [
        {
          id: 'way/1',
          geometry: { type: 'MultiPolygon', coordinates: [rect(0, 0, 10, 10).coordinates] },
        },
      ],
      OUTLINE,
    )
    expect(shape.source).toBe('osm')
    expect(shape.rank).toBeLessThan(RANK.terminal)
    expect(shape.closed).toBe(true)
  })
})

describe('labels', () => {
  const shape = (extra: Partial<MapShape>): MapShape => ({
    id: 'x',
    source: 'asset',
    typeCode: 'SILO',
    rank: RANK.other,
    rings: [],
    closed: true,
    box: { x0: 0, x1: 0, y0: 0, y1: 0 },
    colour: palette.steel,
    name: 'Silo 1',
    anchor: [0, 0],
    minSideM: 52,
    berthLengthM: null,
    lineWidthM: null,
    ...extra,
  })

  it('show when the shorter side is more than 26 px on screen', () => {
    expect(labelFits(shape({ minSideM: 52 }), 0.5)).toBe(false) // exactly 26 px
    expect(labelFits(shape({ minSideM: 52 }), 0.51)).toBe(true)
    expect(labelFits(shape({ minSideM: 10 }), 2)).toBe(false)
  })

  it('show for a berth when its length is more than 50 px on screen', () => {
    const berth = shape({ typeCode: 'BERTH', closed: false, minSideM: null, berthLengthM: 400 })
    expect(labelFits(berth, 0.125)).toBe(false) // exactly 50 px
    expect(labelFits(berth, 0.13)).toBe(true)
  })

  it('never show for a line without a length, or a shape without a name', () => {
    expect(labelFits(shape({ closed: false, minSideM: null }), 20)).toBe(false)
    expect(labelFits(shape({ name: '' }), 20)).toBe(false)
  })
})

describe('hit testing', () => {
  const shapes = assetShapes(
    [
      asset('pad', 'STORAGE_PAD', rect(0, 0, 100, 100)),
      asset(
        'road',
        'ROAD_SEGMENT',
        line([
          [-50, 50],
          [150, 50],
        ]),
        { lineWidthM: 8 },
      ),
      asset('terminal', 'TERMINAL_BOUNDARY', rect(-200, -200, 600, 600)),
    ],
    TYPES,
    FALLBACK,
  )
  const byId = (id: string) => shapes.find((s) => s.id === id)!
  const pad = byId('pad')
  const road = byId('road')
  const terminal = byId('terminal')

  it('hits a polygon inside and misses outside', () => {
    expect(hits(pad, 50, 20, 0)).toBe(true)
    expect(hits(pad, 150, 20, 0)).toBe(false)
  })

  it('hits a line within half its width, or the reach, of it', () => {
    expect(hits(road, 0, 53, 0)).toBe(true) // inside the 8 m road
    expect(hits(road, 0, 56, 0)).toBe(false)
    expect(hits(road, 0, 56, 3)).toBe(false) // the larger of the two, not their sum
    expect(hits(road, 0, 56, 6)).toBe(true)
  })

  it('hits a berth within 6 m of its quay line, or the reach when that is more', () => {
    const berth = {
      ...road,
      id: 'berth',
      typeCode: 'BERTH',
      lineWidthM: null,
      rings: [
        [
          [0, 0],
          [100, 0],
        ] as [number, number][],
      ],
      box: { x0: 0, x1: 100, y0: 0, y1: 0 },
    }
    expect(hits(berth, 50, 5.9, 0)).toBe(true)
    expect(hits(berth, 50, 6.5, 0)).toBe(false)
    expect(hits(berth, 50, 9, 14)).toBe(true) // 7 px at 0.5 px/m
  })

  it('hits a terminal on its edge only: it encloses everything else', () => {
    expect(hits(terminal, 100, 100, 1)).toBe(false)
    expect(hits(terminal, 400, 100, 1)).toBe(true)
  })

  it('picks the topmost shape under the point, and a background shape only below every asset', () => {
    const background = osmShapes([{ id: 'way/9', geometry: rect(300, 300, 20, 20) }], OUTLINE)
    // pad (rank pad) is above the road (rank road) where they overlap
    expect(pick([terminal, road, pad], background, 50, 50, 1)?.id).toBe('pad')
    expect(pick([terminal, road, pad], background, 310, 310, 1)?.id).toBe('way/9')
    expect(pick([terminal, road, pad], background, 100, 300, 1)).toBeNull()
  })

  it('treats a hole as outside', () => {
    const donut = {
      ...pad,
      rings: [
        [
          [0, 0],
          [100, 0],
          [100, 100],
          [0, 100],
        ] as [number, number][],
        [
          [40, 40],
          [60, 40],
          [60, 60],
          [40, 60],
        ] as [number, number][],
      ],
    }
    expect(hits(donut, 10, 10, 0)).toBe(true)
    expect(hits(donut, 50, 50, 0)).toBe(false)
  })
})

describe('boxes', () => {
  it('find their bounds and when they touch', () => {
    expect(
      boxOf([
        [
          [1, 2],
          [5, -3],
        ],
      ]),
    ).toEqual({ x0: 1, x1: 5, y0: -3, y1: 2 })
    expect(boxesTouch({ x0: 0, x1: 10, y0: 0, y1: 10 }, { x0: 10, x1: 20, y0: 5, y1: 6 })).toBe(
      true,
    )
    expect(boxesTouch({ x0: 0, x1: 10, y0: 0, y1: 10 }, { x0: 11, x1: 20, y0: 5, y1: 6 })).toBe(
      false,
    )
  })

  it('the frame used here turns local metres into the same place as lib/geo', () => {
    const [x, y] = fromLonLat(...toLonLat(12, 34))
    expect(x).toBeCloseTo(12, 6)
    expect(y).toBeCloseTo(34, 6)
  })
})
