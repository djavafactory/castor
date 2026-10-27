import { clearActiveFrame, setActiveFrame, fromLonLat } from '@/lib/geo'
import { palette, hex } from '@/features/scene/palette'
import { portFixture } from '@/test/fixtures'
import { Color } from 'three'
import { clipToRect, cellGeometry } from './ground-geometry'
import { WATER_Y } from './layer-heights'
import {
  FOAM_WIDTH_M,
  KERB_HEIGHT_M,
  SHALLOW_M,
  foamItem,
  kerbGeometry,
  quayLine,
  quayWallGeometry,
  waterGeometry,
} from './quay-geometry'
import { LAND_PAD_M, WATER_DEPTH_M, landSceneBox, quayY, stageExtent } from './stage-extent'
import { buildFlatBatches } from './flat-geometry'
import { prismEdges } from './selection'
import { drawPill, screenScale } from './labels'
import { footprintOf } from './footprint'
import type { Port } from '@/lib/api/schemas'

beforeAll(() => {
  setActiveFrame({
    lon0: portFixture.origin.lon,
    lat0: portFixture.origin.lat,
    rotDeg: portFixture.frameRotDeg,
  })
})
afterAll(() => clearActiveFrame())

const port = portFixture as unknown as Port

describe('stage extent', () => {
  it('falls back to the Westports site when the port has no land extent', () => {
    const e = stageExtent({ landExtent: null, quayLine: null })
    expect(e.land).toEqual({ x0: -3500, x1: 6000, y0: 0, y1: 1500 })
    expect(e.x0).toBe(-3500 - LAND_PAD_M)
    expect(e.x1).toBe(6000 + LAND_PAD_M)
    // water spans x -3800..6300 as in the ticket
    const xs = e.waterPolygon.map((p) => p[0])
    expect(Math.min(...xs)).toBe(-3800)
    expect(Math.max(...xs)).toBe(6300)
  })

  it('takes the quay line from the port record, in local metres (quay at y = 0)', () => {
    const e = stageExtent(port)
    expect(e.quay).toHaveLength(2)
    expect(e.quay[0][0]).toBeLessThan(e.quay[1][0])
    for (const [, y] of e.quay) expect(Math.abs(y)).toBeLessThan(60)
    expect(quayY(e.quay, e.quay[0][0])).toBeCloseTo(e.quay[0][1])
    // continued straight past the ends
    expect(Number.isFinite(quayY(e.quay, e.x1))).toBe(true)
  })

  it('cuts the land plane at the quay line so it never covers the water', () => {
    const e = stageExtent({ landExtent: null, quayLine: null })
    const ys = e.landPolygon.map((p) => p[1])
    expect(Math.min(...ys)).toBe(0)
    expect(Math.max(...ys)).toBe(1500 + LAND_PAD_M)
    expect(Math.max(...e.waterPolygon.map((p) => p[1]))).toBe(0)
    expect(Math.min(...e.waterPolygon.map((p) => p[1]))).toBe(-WATER_DEPTH_M)
  })

  it('derives the land box from the land extent polygon', () => {
    const e = stageExtent({
      landExtent: {
        type: 'Polygon',
        coordinates: [
          [
            [101.3225138, 2.9807343],
            [101.2794018, 2.9065568],
            [101.2902746, 2.9001692],
            [101.3333867, 2.9743467],
            [101.3225138, 2.9807343],
          ],
        ],
      },
      quayLine: null,
    })
    // the Westports seed: about 9.5 km along the quay
    expect(e.land.x1 - e.land.x0).toBeGreaterThan(9000)
    expect(e.land.x1 - e.land.x0).toBeLessThan(10000)
    const [x, y] = fromLonLat(101.3225138, 2.9807343)
    expect(x).toBeGreaterThanOrEqual(e.land.x0 - 1e-6)
    expect(y).toBeLessThanOrEqual(e.land.y1 + 1e-6)
  })

  it('has a scene box for the Whole port view', () => {
    const e = stageExtent({ landExtent: null, quayLine: null })
    expect(landSceneBox(e)).toEqual({ x0: -3800, x1: 6300, z0: -(1500 + LAND_PAD_M), z1: 0 })
  })
})

describe('water, foam, wall and kerb', () => {
  const extent = stageExtent({ landExtent: null, quayLine: null })

  it('lays the water at y = -2 on the water side only', () => {
    const g = waterGeometry(extent)
    const pos = g.getAttribute('position')
    for (let i = 0; i < pos.count; i++) {
      expect(pos.getY(i)).toBe(WATER_Y)
      // water side: local y < 0, scene z > 0 (quay at 0)
      expect(pos.getZ(i)).toBeGreaterThanOrEqual(0)
    }
    expect(WATER_Y).toBe(-2)
    const xs = Array.from({ length: pos.count }, (_, i) => pos.getX(i))
    expect(Math.min(...xs)).toBe(-3800)
    expect(Math.max(...xs)).toBe(6300)
  })

  it('fades shallow near the quay to deep beyond 420 m', () => {
    const g = waterGeometry(extent)
    const pos = g.getAttribute('position')
    const col = g.getAttribute('color')
    const at = (dist: number) => {
      for (let i = 0; i < pos.count; i++) {
        if (Math.abs(pos.getZ(i) - dist) < 1e-9)
          return new Color(col.getX(i), col.getY(i), col.getZ(i))
      }
      throw new Error('row missing')
    }
    const shallow = new Color(hex(palette.water.shallow))
    const deep = new Color(hex(palette.water.deep))
    expect(at(0).getHex()).toBe(shallow.getHex())
    expect(at(SHALLOW_M).getHex()).toBe(deep.getHex())
    expect(at(WATER_DEPTH_M).getHex()).toBe(deep.getHex())
    // in between it is between the two
    const mid = at(180)
    expect(mid.getHex()).not.toBe(shallow.getHex())
    expect(mid.getHex()).not.toBe(deep.getHex())
    // the triangles face up
    const idx = g.getIndex()!
    const normal = g.getAttribute('normal')
    expect(idx.count).toBeGreaterThan(0)
    expect(normal.getY(0)).toBeGreaterThan(0.99)
  })

  it('draws a 5 m foam strip along the quay edge on the water', () => {
    const item = foamItem(extent)
    expect(item).toMatchObject({
      kind: 'ribbon',
      layer: 'foam',
      colour: palette.water.foam,
      width: 5,
    })
    expect(FOAM_WIDTH_M).toBe(5)
    const [batch] = buildFlatBatches([item])
    const pos = batch.geometry.getAttribute('position')
    const zs = Array.from({ length: pos.count }, (_, i) => pos.getZ(i))
    // between the quay line (z = 0) and 5 m out
    expect(Math.min(...zs)).toBeCloseTo(0)
    expect(Math.max(...zs)).toBeCloseTo(5)
  })

  it('stands the quay wall from y = -2 to 0, facing the water', () => {
    const g = quayWallGeometry(extent)
    const pos = g.getAttribute('position')
    const ys = Array.from({ length: pos.count }, (_, i) => pos.getY(i))
    expect(Math.min(...ys)).toBe(-2)
    expect(Math.max(...ys)).toBe(0)
    const normal = g.getAttribute('normal')
    expect(normal.getZ(0)).toBeGreaterThan(0.99)
  })

  it('puts a kerb along the quay line, on the land side, facing up', () => {
    const g = kerbGeometry(extent)
    const pos = g.getAttribute('position')
    const ys = Array.from({ length: pos.count }, (_, i) => pos.getY(i))
    expect(Math.max(...ys)).toBe(KERB_HEIGHT_M)
    expect(Math.min(...ys)).toBe(0)
    const zs = Array.from({ length: pos.count }, (_, i) => pos.getZ(i))
    expect(Math.max(...zs)).toBeCloseTo(0)
    expect(Math.min(...zs)).toBeLessThan(0)
    // the top face points up and the front face points to the water
    const normal = g.getAttribute('normal')
    const up = Array.from({ length: normal.count }, (_, i) => normal.getY(i))
    expect(Math.max(...up)).toBeGreaterThan(0.9)
    expect(quayLine(extent).length).toBeGreaterThanOrEqual(2)
  })
})

describe('ground imagery geometry', () => {
  const cell = { x0: 0, x1: 100, y0: 0, y1: 100, ppm: 1, width: 100, height: 100 }

  it('clips a polygon to the cell', () => {
    const clipped = clipToRect(
      [
        [-50, 50],
        [50, 50],
        [50, 150],
        [-50, 150],
      ],
      cell,
    )
    const xs = clipped.map((p) => p[0])
    const ys = clipped.map((p) => p[1])
    expect(Math.min(...xs)).toBe(0)
    expect(Math.max(...xs)).toBe(50)
    expect(Math.min(...ys)).toBe(50)
    expect(Math.max(...ys)).toBe(100)
    expect(
      clipToRect(
        [
          [200, 200],
          [300, 200],
          [300, 300],
        ],
        cell,
      ),
    ).toEqual([])
  })

  it('builds flat geometry with UVs into the canvas', () => {
    const g = cellGeometry(
      [
        [0, 0],
        [100, 0],
        [100, 100],
        [0, 100],
      ],
      cell,
      -0.04,
    )!
    const pos = g.getAttribute('position')
    const uv = g.getAttribute('uv')
    for (let i = 0; i < pos.count; i++) {
      expect(pos.getY(i)).toBeCloseTo(-0.04, 6)
      expect(uv.getX(i)).toBeCloseTo(pos.getX(i) / 100)
      // scene z = -local y; canvas top (v = 1) is the north edge, local y max
      expect(uv.getY(i)).toBeCloseTo(-pos.getZ(i) / 100)
    }
    expect(
      cellGeometry(
        [
          [500, 500],
          [600, 500],
          [600, 600],
        ],
        cell,
        0,
      ),
    ).toBeNull()
  })
})

describe('labels, selection and footprints', () => {
  it('keep a pill the same height on screen at any distance', () => {
    const h = screenScale(24, 800, 30)
    expect(h).toBeCloseTo((24 / 800) * 2 * Math.tan(Math.PI / 12))
    expect(screenScale(24, 1600, 30)).toBeCloseTo(h / 2)
  })

  it('have no pill texture without a canvas (jsdom), without failing', () => {
    expect(drawPill({ text: 'Quay', dot: palette.signal })).toBeNull()
  })

  it('draw the edges of a selected prism, larger for each shell', () => {
    const ring: [number, number][] = [
      [0, 0],
      [10, 0],
      [10, 10],
      [0, 10],
    ]
    const g1 = prismEdges(ring, 8, 1)
    const g2 = prismEdges(ring, 8, 1.1)
    // 4 corners x 3 segments x 2 vertices
    expect(g1.getAttribute('position').count).toBe(24)
    const maxY = (g: typeof g1) =>
      Math.max(
        ...Array.from({ length: g.getAttribute('position').count }, (_, i) =>
          g.getAttribute('position').getY(i),
        ),
      )
    expect(maxY(g1)).toBe(8)
    expect(maxY(g2)).toBeCloseTo(8.8)
  })

  it('take the footprint of an OSM building, with a default height', () => {
    const f = footprintOf({
      type: 'Feature',
      id: 'way/1',
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [101.3066, 2.9534],
            [101.3068, 2.9534],
            [101.3068, 2.9536],
            [101.3066, 2.9534],
          ],
        ],
      },
      properties: {
        kind: 'BUILDING',
        osmRef: 'way/1',
        name: null,
        heightM: null,
        areaM2: null,
        source: 'OSM',
        accuracy: 'APPROXIMATE',
      },
    })!
    expect(f.height).toBe(8)
    expect(f.ring).toHaveLength(4)
  })
})
