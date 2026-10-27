import { beforeEach, describe, expect, it } from 'vitest'
import { fromLonLat, setActiveFrame, toLonLat, type LonLat } from '@/lib/geo'
import {
  MIN_AREA_M2,
  TOO_FEW_LINE,
  TOO_FEW_POLYGON,
  TOO_SMALL,
  finishProblem,
  formatCoordinate,
  insideRing,
  nextIdentity,
  provenanceFor,
  roundLonLat,
  sketchGeometry,
  sketchStats,
} from './draw-rules'

beforeEach(() => setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }))

/** Local-metre corners as the rounded WGS84 points the tool stores. */
const trace = (...local: [number, number][]): LonLat[] =>
  local.map(([x, y]) => roundLonLat(toLonLat(x, y)))

const hexagon = () => trace([0, 0], [40, -10], [80, 0], [80, 40], [40, 55], [0, 40])

describe('measurements', () => {
  it('measures a polygon in local metres: perimeter and area', () => {
    const stats = sketchStats('polygon', trace([0, 0], [30, 0], [30, 20], [0, 20]))
    expect(stats.vertices).toBe(4)
    expect(stats.size).toBeCloseTo(100, 1)
    expect(stats.area).toBeCloseTo(600, 0)
    expect(stats.crossing).toBeNull()
  })

  it('measures a line by its length and gives it no area', () => {
    const stats = sketchStats('line', trace([0, 0], [30, 0], [30, 40]))
    expect(stats.size).toBeCloseTo(70, 1)
    expect(stats.area).toBeNull()
  })

  it('has no area before the third corner', () => {
    expect(sketchStats('polygon', trace([0, 0], [10, 0])).area).toBeNull()
  })

  it('writes the last vertex as latitude, longitude with seven decimals', () => {
    expect(formatCoordinate([101.3066304, 2.9534057])).toBe('2.9534057, 101.3066304')
  })
})

describe('finishing', () => {
  it('asks for three corners or two points first', () => {
    expect(finishProblem('polygon', sketchStats('polygon', trace([0, 0], [9, 0])))).toBe(
      TOO_FEW_POLYGON,
    )
    expect(finishProblem('line', sketchStats('line', trace([0, 0])))).toBe(TOO_FEW_LINE)
    expect(finishProblem('line', sketchStats('line', trace([0, 0], [1, 0])))).toBeNull()
  })

  it('names the crossing edges of a bow tie, counting from 1', () => {
    const bowTie = sketchStats('polygon', trace([0, 0], [20, 20], [20, 0], [0, 20]))
    expect(bowTie.crossing).toEqual([0, 2])
    expect(finishProblem('polygon', bowTie)).toBe(
      'The outline crosses itself (edges 1 and 3). Move or remove points, then finish again.',
    )
  })

  it('checks the crossing before the size', () => {
    const tiny = sketchStats('polygon', trace([0, 0], [1, 1], [1, 0], [0, 1]))
    expect(finishProblem('polygon', tiny)).toMatch(/crosses itself/)
  })

  it(`refuses an outline smaller than ${MIN_AREA_M2} m2`, () => {
    expect(finishProblem('polygon', sketchStats('polygon', trace([0, 0], [1, 0], [1, 3])))).toBe(
      TOO_SMALL,
    )
    expect(
      finishProblem('polygon', sketchStats('polygon', trace([0, 0], [4, 0], [4, 3]))),
    ).toBeNull()
  })

  it('lets a six-corner outline through', () => {
    expect(finishProblem('polygon', sketchStats('polygon', hexagon()))).toBeNull()
  })
})

describe('saved geometry (PRD 19.1)', () => {
  it('closes a polygon ring with its first point and keeps every vertex as drawn', () => {
    const geometry = sketchGeometry('polygon', hexagon())
    expect(geometry.type).toBe('Polygon')
    const ring = geometry.coordinates[0]!
    expect(ring).toHaveLength(hexagon().length + 1)
    expect(ring.slice(0, -1)).toEqual(hexagon())
    expect(ring.at(-1)).toEqual(hexagon()[0])
  })

  it('stores what is shown: rounded to seven decimals, so a reload gives the same WGS84 points', () => {
    const drawn = roundLonLat(toLonLat(12.345678, 9.87654321))
    expect(roundLonLat(drawn)).toEqual(drawn)
    const reloaded = JSON.parse(JSON.stringify(sketchGeometry('line', [drawn, hexagon()[0]!])))
    expect(reloaded.coordinates[0]).toEqual(drawn)
    const [x, y] = fromLonLat(...drawn)
    expect(x).toBeCloseTo(12.345678, 1)
    expect(y).toBeCloseTo(9.87654321, 1)
  })

  it('writes a line as a LineString', () => {
    expect(sketchGeometry('line', hexagon().slice(0, 3)).type).toBe('LineString')
  })
})

describe('provenance', () => {
  it('says approximate when traced from Sentinel-2', () => {
    expect(provenanceFor('sentinel2')).toEqual({
      source: 'SENTINEL2_EOX',
      accuracy: 'APPROXIMATE',
      note: 'Traced from 10 m satellite imagery: approximate, not a survey',
    })
  })

  it('credits the operator on any other basemap', () => {
    expect(provenanceFor('none')).toEqual({
      source: 'OPERATOR',
      accuracy: 'APPROXIMATE',
      note: 'Drawn in the layout designer',
    })
  })
})

describe('names', () => {
  it('numbers after the assets of the same type and skips taken names', () => {
    const type = { code: 'FENCE', label: 'Fence' }
    expect(nextIdentity(type, [])).toEqual({ name: 'Fence 1', code: 'FENCE-01' })
    const assets = [
      { id: 'a', typeCode: 'FENCE', rowVersion: 1, name: 'Fence 2', code: 'FENCE-02' },
    ]
    expect(nextIdentity(type, assets)).toEqual({ name: 'Fence 3', code: 'FENCE-03' })
  })
})

describe('extent', () => {
  it('tells inside from outside of a ring', () => {
    const ring = [
      [0, 0],
      [10, 0],
      [10, 10],
      [0, 10],
      [0, 0],
    ]
    expect(insideRing([5, 5], ring)).toBe(true)
    expect(insideRing([11, 5], ring)).toBe(false)
  })
})
