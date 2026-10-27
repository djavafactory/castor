import { palette } from '@/features/scene/palette'
import type { Local } from '@/lib/geo'
import {
  buildFlatBatches,
  dashSegments,
  fireAccess,
  internalRoad,
  junction,
  osmRoad,
  ribbonEdges,
  roadSegment,
  surfaceArea,
  terminalBoundary,
  type FlatItem,
} from './flat-geometry'
import { LAYERS } from './layer-heights'

const square = (s: number, x = 0, y = 0): Local[] => [
  [x, y],
  [x + s, y],
  [x + s, y + s],
  [x, y + s],
]

describe('layer heights', () => {
  it('are the fixed heights and offsets of the ticket', () => {
    const pairs = Object.fromEntries(Object.entries(LAYERS).map(([k, v]) => [k, [v.y, v.offset]]))
    expect(pairs).toMatchObject({
      satelliteGround: [-0.04, 0],
      land: [0, 0],
      terminalBoundary: [0.02, -0.3],
      surfaceArea: [0.03, -0.5],
      roadSegment: [0.04, -1],
      osmRoad: [0.045, -1.5],
      laydownYard: [0.06, -2],
      bundFloor: [0.06, -2],
      stagingPad: [0.07, -2],
      containerYardPad: [0.07, -2],
      storagePad: [0.08, -2],
      berthStrip: [0.07, -2.5],
      internalRoad: [0.07, -2.5],
      fireAccess: [0.07, -3],
      junction: [0.07, -3],
      drainageZone: [0.09, -3],
      exclusionZone: [0.1, -3],
      hoverPad: [0.14, -4],
      ghostPad: [0.08, -4],
    })
  })

  it('stack in the order of the ticket, with marks above what they are drawn on', () => {
    const order = [
      'satelliteGround',
      'land',
      'terminalBoundary',
      'surfaceArea',
      'roadSegment',
      'osmRoad',
      'laydownYard',
      'stagingPad',
      'storagePad',
      'drainageZone',
      'exclusionZone',
      'hoverPad',
    ] as const
    order.slice(1).forEach((key, i) => expect(LAYERS[key].y).toBeGreaterThan(LAYERS[order[i]].y))
    expect(LAYERS.marking.y).toBeGreaterThan(LAYERS.roadSegment.y)
    expect(LAYERS.marking.y).toBeGreaterThan(LAYERS.terminalBoundary.y)
    expect(LAYERS.markingTop.y).toBeGreaterThan(LAYERS.fireAccess.y)
    expect(LAYERS.markingTop.y).toBeGreaterThan(LAYERS.junction.y)
    // Distinct heights are at least 5 mm apart, enough for the log depth buffer
    const ys = [...new Set(Object.values(LAYERS).map((l) => l.y))].sort((a, b) => a - b)
    ys.slice(1).forEach((y, i) => expect(y - ys[i]).toBeGreaterThanOrEqual(0.005 - 1e-9))
  })
})

describe('flat batches', () => {
  const items: FlatItem[] = [
    ...surfaceArea(square(10), palette.surface.concrete),
    ...surfaceArea(square(10, 20), palette.surface.concrete),
    ...surfaceArea(square(10, 40), palette.surface.grass),
    ...roadSegment(
      [
        [0, 0],
        [50, 0],
        [50, 40],
      ],
      8,
    ),
    ...internalRoad(
      [
        [0, 20],
        [30, 20],
      ],
      6,
    ),
    ...fireAccess(
      [
        [0, 30],
        [30, 30],
      ],
      4,
    ),
    ...junction([60, 60], 12),
    ...osmRoad(
      [
        [0, 70],
        [40, 70],
        [40, 100],
      ],
      7,
    ),
    ...terminalBoundary(square(100, 200, 200), 'T1'),
    ...terminalBoundary(square(100, 400, 200), 'T6'),
  ]
  const batches = buildFlatBatches(items)

  it('merge into one geometry per layer and colour', () => {
    const keys = batches.map((b) => b.key)
    expect(new Set(keys).size).toBe(keys.length)
    const concrete = batches.filter(
      (b) => b.layer === 'surfaceArea' && b.colour === palette.surface.concrete,
    )
    expect(concrete).toHaveLength(1)
    // two squares of two triangles each
    expect(concrete[0].geometry.getIndex()!.count).toBe(12)
    expect(batches.find((b) => b.key === `surfaceArea|${palette.surface.grass}`)).toBeDefined()
  })

  it('put every vertex at the height of its layer (QA: within 0.001)', () => {
    for (const b of batches) {
      const pos = b.geometry.getAttribute('position')
      expect(pos.count).toBeGreaterThan(0)
      for (let i = 0; i < pos.count; i++) {
        expect(Math.abs(pos.getY(i) - LAYERS[b.layer].y)).toBeLessThan(0.001)
      }
    }
  })

  it('face every triangle up', () => {
    for (const b of batches) {
      const pos = b.geometry.getAttribute('position')
      const idx = b.geometry.getIndex()!
      for (let t = 0; t < idx.count; t += 3) {
        const [a, c, d] = [idx.getX(t), idx.getX(t + 1), idx.getX(t + 2)]
        const ux = pos.getX(c) - pos.getX(a)
        const uz = pos.getZ(c) - pos.getZ(a)
        const vx = pos.getX(d) - pos.getX(a)
        const vz = pos.getZ(d) - pos.getZ(a)
        // normal y of a triangle in the xz plane is uz * vx - ux * vz
        expect(uz * vx - ux * vz).toBeGreaterThanOrEqual(0)
      }
    }
  })

  it('use the colours of the ticket', () => {
    const has = (layer: string, colour: string) =>
      batches.some((b) => b.layer === layer && b.colour === colour)
    expect(has('roadSegment', palette.road)).toBe(true)
    expect(has('marking', palette.white)).toBe(true) // white dashed centre line
    expect(has('internalRoad', palette.internalRoad)).toBe(true)
    expect(has('fireAccess', palette.fireAccessFill)).toBe(true)
    expect(has('markingTop', palette.brick)).toBe(true) // fire access dashes
    expect(has('markingTop', palette.white)).toBe(true) // stop bars
    expect(has('terminalBoundary', palette.terminalT1)).toBe(true)
    expect(has('terminalBoundary', palette.terminalT6)).toBe(true)
    expect(has('osmRoad', palette.osmRoad)).toBe(true)
  })

  it('draw four stop bars per junction', () => {
    const bars = buildFlatBatches(junction([0, 0], 12).filter((i) => i.kind === 'bars'))
    expect(bars[0].geometry.getIndex()!.count).toBe(4 * 6)
  })

  it('ignore degenerate input', () => {
    const empty = buildFlatBatches([
      {
        kind: 'polygon',
        layer: 'land',
        colour: palette.land,
        ring: [
          [0, 0],
          [1, 1],
        ],
      },
      { kind: 'ribbon', layer: 'osmRoad', colour: palette.osmRoad, points: [[0, 0]], width: 3 },
    ])
    for (const b of empty) expect(b.geometry.getIndex()!.count).toBe(0)
  })

  it('keep holes open', () => {
    const [b] = buildFlatBatches([
      {
        kind: 'polygon',
        layer: 'bundFloor',
        colour: palette.pad,
        ring: square(10),
        holes: [
          [
            [4, 4],
            [6, 4],
            [6, 6],
            [4, 6],
          ],
        ],
      },
    ])
    let area = 0
    const pos = b.geometry.getAttribute('position')
    const idx = b.geometry.getIndex()!
    for (let t = 0; t < idx.count; t += 3) {
      const [a, c, d] = [idx.getX(t), idx.getX(t + 1), idx.getX(t + 2)]
      area +=
        Math.abs(
          (pos.getX(c) - pos.getX(a)) * (pos.getZ(d) - pos.getZ(a)) -
            (pos.getX(d) - pos.getX(a)) * (pos.getZ(c) - pos.getZ(a)),
        ) / 2
    }
    expect(area).toBeCloseTo(96)
  })
})

describe('ribbons and dashes', () => {
  it('offset a straight line by half the width', () => {
    const [l, r] = ribbonEdges(
      [
        [0, 0],
        [10, 0],
      ],
      6,
    )
    expect(l).toEqual([
      [0, 3],
      [10, 3],
    ])
    expect(r).toEqual([
      [0, -3],
      [10, -3],
    ])
  })

  it('mitre a right-angle corner so the width stays constant', () => {
    const [l, r] = ribbonEdges(
      [
        [0, 0],
        [10, 0],
        [10, 10],
      ],
      4,
    )
    expect(l[1][0]).toBeCloseTo(8)
    expect(l[1][1]).toBeCloseTo(2)
    expect(r[1][0]).toBeCloseTo(12)
    expect(r[1][1]).toBeCloseTo(-2)
  })

  it('limit the mitre at a sharp turn', () => {
    const [l] = ribbonEdges(
      [
        [0, 0],
        [10, 0],
        [0, 0.5],
      ],
      2,
    )
    expect(Math.hypot(l[1][0] - 10, l[1][1])).toBeLessThanOrEqual(1 / 0.4 + 1e-9)
  })

  it('split a line into dashes and gaps', () => {
    const dashes = dashSegments(
      [
        [0, 0],
        [20, 0],
      ],
      3,
      4,
    )
    expect(dashes).toEqual([
      [
        [0, 0],
        [3, 0],
      ],
      [
        [7, 0],
        [10, 0],
      ],
      [
        [14, 0],
        [17, 0],
      ],
    ])
    const bent = dashSegments(
      [
        [0, 0],
        [2, 0],
        [2, 5],
      ],
      3,
      1,
    )
    expect(bent[0]).toEqual([
      [0, 0],
      [2, 0],
      [2, 1],
    ])
  })
})
