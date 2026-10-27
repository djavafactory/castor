import { Color } from 'three'
import { createFrame, type Local } from '@/lib/geo'
import type { ReferenceFeature } from '@/lib/reference-api'
import {
  UNIT_COLOUR,
  UNIT_HEIGHT_M,
  WALL_COLOUR,
  buildBuildingsGeometry,
  lighten,
} from './building-geometry'

const frame = createFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
const toLocal = (lon: number, lat: number): Local => frame.fromLonLat(lon, lat)

/** A building given in local metres, stored as WGS84 like the API does. */
function building(
  osmRef: string,
  rings: Local[][],
  heightM: number | null = 10,
  name: string | null = null,
): ReferenceFeature {
  const coordinates = rings.map((ring) => {
    const closed = [...ring, ring[0]!]
    return closed.map(([x, y]) => frame.toLonLat(x, y))
  })
  return {
    type: 'Feature',
    id: osmRef,
    geometry: { type: 'Polygon', coordinates },
    properties: {
      kind: 'BUILDING',
      osmRef,
      name,
      heightM,
      areaM2: null,
      source: 'OSM',
      accuracy: 'APPROXIMATE',
    },
  }
}

const rect = (x: number, y: number, w: number, h: number): Local[] => [
  [x, y],
  [x + w, y],
  [x + w, y + h],
  [x, y + h],
]

function rgbAt(colours: ArrayLike<number>, vertex: number): number {
  return new Color(
    colours[vertex * 3]!,
    colours[vertex * 3 + 1]!,
    colours[vertex * 3 + 2]!,
  ).getHex()
}

function faceColour(built: ReturnType<typeof buildBuildingsGeometry>, face: number): number {
  return rgbAt(built.geometry.getAttribute('color').array, face * 3)
}

describe('lighten', () => {
  it('moves each channel 35 % towards white', () => {
    expect(lighten(0x000000, 0.35)).toBe(0x595959)
    expect(lighten(0xffffff, 0.35)).toBe(0xffffff)
    expect(lighten(WALL_COLOUR, 0.35).toString(16)).toBe('bdc3ca')
  })
})

describe('buildBuildingsGeometry', () => {
  it('builds a box: 4 walls and a roof, wall and roof colours as specified', () => {
    const built = buildBuildingsGeometry([building('way/1', [rect(0, 0, 8, 6)])], toLocal)

    // 4 walls x 2 triangles + 2 roof triangles; no rooftop unit on a small building
    expect(built.faceToFeature).toHaveLength(10)
    const colours = new Set(
      Array.from({ length: 10 }, (_, face) => faceColour(built, face).toString(16)),
    )
    expect(colours).toEqual(new Set(['9aa3ad', 'bdc3ca']))
    expect(built.geometry.boundingBox!.max.y).toBeCloseTo(10, 4)
    expect(built.geometry.boundingBox!.min.y).toBeCloseTo(0, 4)
  })

  it('places points at scene x = local x, z = -local y', () => {
    const built = buildBuildingsGeometry([building('way/1', [rect(100, 50, 8, 6)])], toLocal)
    const box = built.geometry.boundingBox!
    expect(box.min.x).toBeCloseTo(100, 2)
    expect(box.max.x).toBeCloseTo(108, 2)
    expect(box.min.z).toBeCloseTo(-56, 2)
    expect(box.max.z).toBeCloseTo(-50, 2)
  })

  it('faces every triangle along its stored normal (outward walls, upward roof)', () => {
    const built = buildBuildingsGeometry([building('way/1', [rect(0, 0, 8, 6)])], toLocal)
    const p = built.geometry.getAttribute('position')
    const n = built.geometry.getAttribute('normal')
    for (let face = 0; face < built.faceToFeature.length; face += 1) {
      const [a, b, c] = [0, 1, 2].map((k) => face * 3 + k)
      const u = [0, 1, 2].map((i) => p.array[b! * 3 + i]! - p.array[a! * 3 + i]!)
      const v = [0, 1, 2].map((i) => p.array[c! * 3 + i]! - p.array[a! * 3 + i]!)
      const cross = [
        u[1]! * v[2]! - u[2]! * v[1]!,
        u[2]! * v[0]! - u[0]! * v[2]!,
        u[0]! * v[1]! - u[1]! * v[0]!,
      ]
      const dot = cross[0]! * n.getX(a!) + cross[1]! * n.getY(a!) + cross[2]! * n.getZ(a!)
      expect(dot).toBeGreaterThan(0)
    }
    // a wall of the south edge (local y = 0) points to local -y, which is scene +z
    const normals = Array.from({ length: p.count }, (_, i) => [n.getX(i), n.getY(i), n.getZ(i)])
    expect(normals.some(([x, y, z]) => Math.abs(x!) < 1e-6 && y === 0 && z! > 0.99)).toBe(true)
  })

  it('keeps holes: a courtyard adds inner walls and no roof over the hole', () => {
    const ring = buildBuildingsGeometry(
      [building('way/2', [rect(0, 0, 40, 40), rect(10, 10, 20, 20)], 10)],
      toLocal,
    )
    // outer 4 + hole 4 walls (16 triangles); roof triangles: more than the solid roof, covering a ring
    const roofTriangles = ring.faceToFeature.length - 16
    expect(roofTriangles).toBeGreaterThanOrEqual(8)

    // The roof triangles together cover 1600 - 400 = 1200 m2
    const p = ring.geometry.getAttribute('position')
    let area = 0
    for (let face = 0; face < ring.faceToFeature.length; face += 1) {
      if (faceColour(ring, face) !== 0xbdc3ca) continue
      const i = face * 3
      area +=
        Math.abs(
          (p.getX(i + 1) - p.getX(i)) * (p.getZ(i + 2) - p.getZ(i)) -
            (p.getX(i + 2) - p.getX(i)) * (p.getZ(i + 1) - p.getZ(i)),
        ) / 2
    }
    expect(area).toBeCloseTo(1200, 0)
  })

  it('adds a 1.6 m rooftop unit only on buildings larger than 12 x 10 m', () => {
    const small = buildBuildingsGeometry([building('way/s', [rect(0, 0, 12, 10)])], toLocal)
    const large = buildBuildingsGeometry([building('way/l', [rect(0, 0, 20, 14)], 10)], toLocal)

    expect(small.faceToFeature).toHaveLength(10) // exactly 12 x 10 is not larger
    // unit: 4 walls x 2 + top 2 triangles
    expect(large.faceToFeature).toHaveLength(10 + 10)
    expect(large.geometry.boundingBox!.max.y).toBeCloseTo(10 + UNIT_HEIGHT_M, 4)

    const unitFaces = Array.from({ length: large.faceToFeature.length }, (_, f) => f).filter(
      (f) => faceColour(large, f) === new Color().setHex(UNIT_COLOUR).getHex(),
    )
    expect(unitFaces).toHaveLength(10)

    // 22 % x 30 % of 20 x 14 m = 4.4 x 4.2 m, centred
    const p = large.geometry.getAttribute('position')
    const xs = unitFaces.flatMap((f) => [0, 1, 2].map((k) => p.getX(f * 3 + k)))
    const zs = unitFaces.flatMap((f) => [0, 1, 2].map((k) => p.getZ(f * 3 + k)))
    const dims = [Math.max(...xs) - Math.min(...xs), Math.max(...zs) - Math.min(...zs)].sort()
    expect(dims[0]).toBeCloseTo(4.2, 1)
    expect(dims[1]).toBeCloseTo(4.4, 1)
  })

  it('needs both sides beyond the limits, whichever way the building is turned', () => {
    const narrow = buildBuildingsGeometry([building('way/n', [rect(0, 0, 30, 9)])], toLocal)
    expect(narrow.faceToFeature).toHaveLength(10)
  })

  it('skips the rooftop unit when it would not sit on the roof (L-shaped footprint)', () => {
    const l: Local[] = [
      [0, 0],
      [30, 0],
      [30, 11],
      [11, 11],
      [11, 30],
      [0, 30],
    ]
    const built = buildBuildingsGeometry([building('way/l', [l])], toLocal)
    const unit = Array.from({ length: built.faceToFeature.length }, (_, f) => f).filter(
      (f) => faceColour(built, f) === new Color().setHex(UNIT_COLOUR).getHex(),
    )
    expect(unit).toHaveLength(0)
  })

  it('maps every face to its building so picking finds the right one', () => {
    const built = buildBuildingsGeometry(
      [
        building('way/1', [rect(0, 0, 8, 6)], 10),
        building('way/2', [rect(100, 0, 20, 14)], 12),
        building('way/3', [rect(200, 0, 8, 6)], 9),
      ],
      toLocal,
    )
    const counts = new Map<string, number>()
    for (let face = 0; face < built.faceToFeature.length; face += 1) {
      const ref = built.featureAtFace(face)!.osmRef
      counts.set(ref, (counts.get(ref) ?? 0) + 1)
    }
    expect(Object.fromEntries(counts)).toEqual({ 'way/1': 10, 'way/2': 20, 'way/3': 10 })

    // the face belongs to the building whose x range holds its position
    const p = built.geometry.getAttribute('position')
    for (let face = 0; face < built.faceToFeature.length; face += 1) {
      const x = p.getX(face * 3)
      const expected = x < 50 ? 'way/1' : x < 150 ? 'way/2' : 'way/3'
      expect(built.featureAtFace(face)!.osmRef).toBe(expected)
    }
    expect(built.featureAtFace(9999)).toBeUndefined()
  })

  it('draws a building without a height at 8 m and skips unusable footprints', () => {
    const noHeight = building('way/1', [rect(0, 0, 8, 6)], null)
    const point: ReferenceFeature = {
      ...noHeight,
      id: 'way/2',
      geometry: { type: 'LineString', coordinates: [[101.3, 2.95]] },
    }
    const built = buildBuildingsGeometry([noHeight, point], toLocal)
    expect(built.geometry.boundingBox!.max.y).toBeCloseTo(8, 4)
    expect(built.features.map((f) => f.osmRef)).toEqual(['way/1'])
  })

  it('is a single geometry however many buildings there are', () => {
    const many = Array.from({ length: 127 }, (_, i) =>
      building(`way/${i}`, [rect(i * 30, 0, 20, 14)]),
    )
    const built = buildBuildingsGeometry(many, toLocal)
    expect(built.features).toHaveLength(127)
    expect(built.geometry.getAttribute('position').count).toBe(built.faceToFeature.length * 3)
  })
})
