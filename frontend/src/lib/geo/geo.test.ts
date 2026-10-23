import { PerspectiveCamera, Plane, Raycaster, Vector2, Vector3 } from 'three'
import { afterEach, describe, expect, it } from 'vitest'
import {
  circle,
  clearActiveFrame,
  createFrame,
  fromLonLat,
  geodesicDistance,
  hasActiveFrame,
  headingToRotation,
  lineLength,
  lonLatToScene,
  metresPerDegree,
  polygonArea,
  polygonPerimeter,
  rectangle,
  rotationToHeading,
  sceneToLonLat,
  segmentsIntersect,
  selfIntersection,
  setActiveFrame,
  toLonLat,
  trueBearing,
  type Local,
  type LonLat,
} from '.'
import fixtureJson from '../../../../fixtures/coordinates.json'

interface Fixture {
  frame: { lon0: number; lat0: number; rotDeg: number }
  tolerances: { backendVsFrontendM: number; roundTripM: number; controlPointM: number }
  controlPoints: { name: string; lon: number; lat: number; x: number; y: number }[]
  points: { lon: number; lat: number; x: number; y: number }[]
  rectangles: {
    anchor: LonLat
    heading: number
    length: number
    width: number
    rotation: number
    corners: LonLat[]
  }[]
  headings: { heading: number; rotation: number }[]
}

// Shared with the backend suite (QuayFrameFixtureTest)
const fixture = fixtureJson as unknown as Fixture
const f = fixture.frame
const [mLat, mLon] = metresPerDegree(f.lat0)

/** Metres between two WGS84 points using the same local-degree scale (good to micrometres over centimetres). */
function metresApart(a: LonLat, b: LonLat): number {
  return Math.hypot((a[0] - b[0]) * mLon, (a[1] - b[1]) * mLat)
}

afterEach(clearActiveFrame)

describe('shared fixtures', () => {
  const frame = createFrame(f)

  it('reproduces the SETUP-03 control points within 0.1 m', () => {
    for (const p of fixture.controlPoints) {
      const [x, y] = frame.fromLonLat(p.lon, p.lat)
      expect(Math.hypot(x - p.x, y - p.y), p.name).toBeLessThan(fixture.tolerances.controlPointM)
    }
  })

  it('matches the shared local coordinates of 20 points within 1 cm', () => {
    expect(fixture.points).toHaveLength(20)
    for (const p of fixture.points) {
      const [x, y] = frame.fromLonLat(p.lon, p.lat)
      expect(Math.hypot(x - p.x, y - p.y)).toBeLessThan(fixture.tolerances.backendVsFrontendM)
    }
  })

  it('round-trips WGS84 -> local -> WGS84 below 1 mm', () => {
    for (const p of [...fixture.points, ...fixture.controlPoints]) {
      const [x, y] = frame.fromLonLat(p.lon, p.lat)
      expect(metresApart(frame.toLonLat(x, y), [p.lon, p.lat])).toBeLessThan(
        fixture.tolerances.roundTripM,
      )
    }
  })

  it('builds the 5 shared rectangles within 1 cm', () => {
    expect(fixture.rectangles).toHaveLength(5)
    setActiveFrame(f)
    for (const r of fixture.rectangles) {
      const corners = rectangle(r.anchor, r.heading, r.length, r.width)
      expect(corners).toHaveLength(4)
      corners.forEach((c, i) => {
        expect(metresApart(c, r.corners[i]!)).toBeLessThan(fixture.tolerances.backendVsFrontendM)
      })
      expect(frame.headingToRotation(r.heading)).toBeCloseTo(r.rotation, 9)
    }
  })

  it('converts headings the same way as the shared table', () => {
    for (const h of fixture.headings) {
      expect(frame.headingToRotation(h.heading)).toBeCloseTo(h.rotation, 9)
      expect(frame.rotationToHeading(h.rotation)).toBeCloseTo(h.heading, 9)
    }
  })
})

describe('active frame', () => {
  it('refuses to convert before the port frame is loaded', () => {
    expect(hasActiveFrame()).toBe(false)
    expect(() => toLonLat(0, 0)).toThrow(/not loaded/)
  })

  it('maps the origin to local zero and back', () => {
    setActiveFrame(f)
    expect(fromLonLat(f.lon0, f.lat0)[0]).toBeCloseTo(0, 9)
    expect(toLonLat(0, 0)).toEqual([f.lon0, f.lat0])
  })

  it('uses scene axes X = x, Y = height, Z = -y', () => {
    setActiveFrame(f)
    const [lon, lat] = toLonLat(120, 35)
    const [sx, sy, sz] = lonLatToScene(lon, lat, 4)
    expect(sx).toBeCloseTo(120, 6)
    expect(sy).toBe(4)
    expect(sz).toBeCloseTo(-35, 6)
    const back = sceneToLonLat(sx, sz)
    expect(metresApart(back, [lon, lat])).toBeLessThan(1e-3)
  })

  it('builds a 48-segment circle of the requested radius', () => {
    setActiveFrame(f)
    const ring = circle([f.lon0, f.lat0], 25)
    expect(ring).toHaveLength(48)
    for (const p of ring) {
      const [x, y] = fromLonLat(p[0], p[1])
      expect(Math.hypot(x, y)).toBeCloseTo(25, 4)
    }
  })

  it('puts a point picked in 3D at the same lon/lat as the conversion of its ground position', () => {
    setActiveFrame(f)
    const camera = new PerspectiveCamera(50, 1, 1, 5000)
    camera.position.set(0, 400, 300)
    camera.lookAt(0, 0, 0)
    camera.updateMatrixWorld()
    const ray = new Raycaster()
    ray.setFromCamera(new Vector2(0.2, -0.3), camera)
    const hit = ray.ray.intersectPlane(new Plane(new Vector3(0, 1, 0), 0), new Vector3())!
    const picked = sceneToLonLat(hit.x, hit.z)
    const expected = createFrame(f).toLonLat(hit.x, -hit.z)
    expect(metresApart(picked, expected)).toBeLessThan(1e-6)
    expect(lonLatToScene(picked[0], picked[1])[0]).toBeCloseTo(hit.x, 6)
  })
})

describe('headings', () => {
  const frame = createFrame(f)
  it('points local +X at heading 210.3 and local +Y at 120.3 (SETUP-03)', () => {
    expect(frame.rotationToHeading(0)).toBeCloseTo(210.299, 3)
    expect(frame.rotationToHeading(90)).toBeCloseTo(120.299, 3)
  })

  it('normalises into the documented ranges', () => {
    expect(frame.headingToRotation(0)).toBeGreaterThan(-180)
    expect(frame.headingToRotation(0)).toBeLessThanOrEqual(180)
    expect(frame.rotationToHeading(-500)).toBeGreaterThanOrEqual(0)
    expect(frame.rotationToHeading(-500)).toBeLessThan(360)
    expect(rotationToHeading).toBeTypeOf('function')
    expect(headingToRotation).toBeTypeOf('function')
  })
})

describe('local measures', () => {
  const square: Local[] = [
    [0, 0],
    [10, 0],
    [10, 5],
    [0, 5],
  ]
  it('measures area, perimeter and length in local metres', () => {
    expect(polygonArea(square)).toBe(50)
    expect(polygonArea([...square].reverse())).toBe(50)
    expect(polygonPerimeter(square)).toBe(30)
    expect(polygonPerimeter([[0, 0]])).toBe(0)
    expect(
      lineLength([
        [0, 0],
        [3, 4],
      ]),
    ).toBe(5)
  })
})

describe('intersections', () => {
  it('detects crossing, touching and disjoint segments', () => {
    expect(segmentsIntersect([0, 0], [4, 4], [0, 4], [4, 0])).toBe(true)
    expect(segmentsIntersect([0, 0], [2, 0], [2, 0], [2, 2])).toBe(true)
    expect(segmentsIntersect([0, 0], [1, 0], [2, 0], [3, 0])).toBe(false)
    expect(segmentsIntersect([0, 0], [4, 0], [1, 0], [3, 0])).toBe(true)
    expect(segmentsIntersect([0, 0], [1, 1], [0, 1], [0.2, 2])).toBe(false)
  })

  it('returns the first crossing edge pair of a bow-tie and null for a simple ring', () => {
    expect(
      selfIntersection([
        [0, 0],
        [4, 4],
        [4, 0],
        [0, 4],
      ]),
    ).toEqual([0, 2])
    expect(
      selfIntersection([
        [0, 0],
        [4, 0],
        [4, 4],
        [0, 4],
      ]),
    ).toBeNull()
    expect(
      selfIntersection([
        [0, 0],
        [1, 1],
        [2, 2],
      ]),
    ).toBeNull()
  })

  it('flags an edge that doubles back over its neighbour', () => {
    expect(
      selfIntersection([
        [0, 0],
        [4, 0],
        [2, 0],
        [2, 3],
      ]),
    ).toEqual([0, 1])
    expect(
      selfIntersection([
        [0, 0],
        [4, 0],
        [4, 3],
        [6, 0],
      ]),
    ).toEqual([0, 3])
  })
})

describe('geodesic', () => {
  it('matches the Vincenty reference (Flinders Peak to Buninyong)', () => {
    const a: LonLat = [144 + 25 / 60 + 29.5244 / 3600, -(37 + 57 / 60 + 3.7203 / 3600)]
    const b: LonLat = [143 + 55 / 60 + 35.3839 / 3600, -(37 + 39 / 60 + 10.1561 / 3600)]
    expect(geodesicDistance(a, b)).toBeCloseTo(54972.271, 2)
    expect(trueBearing(a, b)).toBeCloseTo(306 + 52 / 60 + 5.37 / 3600, 4)
  })

  it('is zero for identical points and wraps bearings into 0..360', () => {
    expect(geodesicDistance([101.3, 2.95], [101.3, 2.95])).toBe(0)
    expect(trueBearing([101.3, 2.95], [101.2, 2.95])).toBeGreaterThan(260)
  })
})
