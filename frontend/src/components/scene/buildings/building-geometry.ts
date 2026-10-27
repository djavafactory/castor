import { BufferAttribute, BufferGeometry, Color, ShapeUtils, Vector2 } from 'three'
import { fromLonLat, signedArea, type Local } from '@/lib/geo'
import type { ReferenceFeature } from '@/lib/reference-api'

export const WALL_COLOUR = 0x9aa3ad
export const ROOF_LIGHTENING = 0.35
export const UNIT_COLOUR = 0x8e96a1
/** Used when OpenStreetMap gave no height. */
export const DEFAULT_HEIGHT_M = 8
export const UNIT_HEIGHT_M = 1.6
/** A rooftop unit is added when the footprint is larger than this (long side x short side). */
export const UNIT_MIN_LONG_M = 12
export const UNIT_MIN_SHORT_M = 10
/** Share of the footprint length and width the rooftop unit covers. */
export const UNIT_LENGTH_SHARE = 0.22
export const UNIT_WIDTH_SHARE = 0.3

export type BuildingProperties = ReferenceFeature['properties']

export interface BuildingsGeometry {
  /** Every building in one non-indexed geometry: one mesh, one draw call. */
  geometry: BufferGeometry
  /** Feature index of each triangle (face), for picking. */
  faceToFeature: Uint32Array
  /** Properties of the features that made it into the geometry, in feature-index order. */
  features: BuildingProperties[]
  /** Looks up the building a raycast face belongs to. */
  featureAtFace: (faceIndex: number) => BuildingProperties | undefined
}

type Ring = Local[]

/** A colour moved towards white by the given share, computed on the sRGB channels as designers do. */
export function lighten(hex: number, share: number): number {
  const channel = (shift: number) => {
    const c = (hex >> shift) & 0xff
    return Math.round(c + (255 - c) * share)
  }
  return (channel(16) << 16) | (channel(8) << 8) | channel(0)
}

/**
 * Extrudes every building footprint (holes kept) to its height and merges the result into one geometry.
 * Walls get WALL_COLOUR, the roof the same colour 35 % lighter, and a rooftop unit sits on large buildings.
 * Positions come from lib/geo: scene x = local x, y = up, z = -local y.
 */
export function buildBuildingsGeometry(
  buildings: readonly ReferenceFeature[],
  toLocal: (lon: number, lat: number) => Local = fromLonLat,
): BuildingsGeometry {
  const positions: number[] = []
  const normals: number[] = []
  const colours: number[] = []
  const faceFeature: number[] = []
  const features: BuildingProperties[] = []

  const wall = toRgb(WALL_COLOUR)
  const roof = toRgb(lighten(WALL_COLOUR, ROOF_LIGHTENING))
  const unit = toRgb(UNIT_COLOUR)

  type Point3 = [number, number, number]

  /** Adds one triangle facing `normal` whatever order its corners come in. */
  function triangle(
    a: Point3,
    b: Point3,
    c: Point3,
    normal: Point3,
    colour: Point3,
    featureIndex: number,
  ) {
    const ux = b[0] - a[0]
    const uy = b[1] - a[1]
    const uz = b[2] - a[2]
    const vx = c[0] - a[0]
    const vy = c[1] - a[1]
    const vz = c[2] - a[2]
    const facing =
      (uy * vz - uz * vy) * normal[0] +
      (uz * vx - ux * vz) * normal[1] +
      (ux * vy - uy * vx) * normal[2]
    const corners = facing >= 0 ? [a, b, c] : [a, c, b]
    for (const p of corners) {
      positions.push(p[0], p[1], p[2])
      normals.push(normal[0], normal[1], normal[2])
      colours.push(colour[0], colour[1], colour[2])
    }
    faceFeature.push(featureIndex)
  }

  /** Vertical faces along every edge of a ring whose solid side is on the left of the walking direction. */
  function walls(ring: Ring, bottom: number, top: number, colour: Point3, featureIndex: number) {
    for (let i = 0; i < ring.length; i += 1) {
      const [ax, ay] = ring[i]!
      const [bx, by] = ring[(i + 1) % ring.length]!
      const length = Math.hypot(bx - ax, by - ay)
      if (length === 0) continue
      // right-hand side of the edge in local x/y, which is away from the solid; scene z = -local y
      const normal: Point3 = [(by - ay) / length, 0, (bx - ax) / length]
      const a0: Point3 = [ax, bottom, -ay]
      const b0: Point3 = [bx, bottom, -by]
      const b1: Point3 = [bx, top, -by]
      const a1: Point3 = [ax, top, -ay]
      triangle(a0, b0, b1, normal, colour, featureIndex)
      triangle(a0, b1, a1, normal, colour, featureIndex)
    }
  }

  for (const feature of buildings) {
    const rings = footprint(feature, toLocal)
    if (!rings) continue
    const [outer, ...holes] = rings
    const featureIndex = features.length
    features.push(feature.properties)
    const height =
      feature.properties.heightM && feature.properties.heightM > 0
        ? feature.properties.heightM
        : DEFAULT_HEIGHT_M

    for (const ring of rings) walls(ring, 0, height, wall, featureIndex)

    const vertices = rings.flat().map(([x, y]) => new Vector2(x, y))
    const triangles = ShapeUtils.triangulateShape(
      outer!.map(([x, y]) => new Vector2(x, y)),
      holes.map((h) => h.map(([x, y]) => new Vector2(x, y))),
    )
    for (const [i, j, k] of triangles) {
      const a = vertices[i!]!
      const b = vertices[j!]!
      const c = vertices[k!]!
      triangle(
        [a.x, height, -a.y],
        [b.x, height, -b.y],
        [c.x, height, -c.y],
        [0, 1, 0],
        roof,
        featureIndex,
      )
    }

    const box = rooftopUnit(outer!, holes)
    if (box) {
      const top = height + UNIT_HEIGHT_M
      walls(box, height, top, unit, featureIndex)
      const [first, ...rest] = box
      for (let i = 1; i < rest.length; i += 1) {
        const a = first!
        const b = rest[i - 1]!
        const c = rest[i]!
        triangle(
          [a[0], top, -a[1]],
          [b[0], top, -b[1]],
          [c[0], top, -c[1]],
          [0, 1, 0],
          unit,
          featureIndex,
        )
      }
    }
  }

  const geometry = new BufferGeometry()
  geometry.setAttribute('position', new BufferAttribute(new Float32Array(positions), 3))
  geometry.setAttribute('normal', new BufferAttribute(new Float32Array(normals), 3))
  geometry.setAttribute('color', new BufferAttribute(new Float32Array(colours), 3))
  geometry.computeBoundingSphere()
  geometry.computeBoundingBox()

  const faceToFeature = Uint32Array.from(faceFeature)
  return {
    geometry,
    faceToFeature,
    features,
    featureAtFace: (faceIndex) => {
      const index = faceToFeature[faceIndex]
      return index === undefined ? undefined : features[index]
    },
  }
}

/** The rings of a Polygon feature in local metres: outer counter-clockwise, holes clockwise, closing point dropped. */
function footprint(
  feature: ReferenceFeature,
  toLocal: (lon: number, lat: number) => Local,
): Ring[] | null {
  const geometry = feature.geometry
  if (geometry.type !== 'Polygon') return null
  const rings: Ring[] = []
  for (const [index, coordinates] of (geometry.coordinates as number[][][]).entries()) {
    let ring = coordinates.map(([lon, lat]) => toLocal(lon!, lat!))
    const first = ring[0]
    const last = ring[ring.length - 1]
    if (first && last && first[0] === last[0] && first[1] === last[1]) ring = ring.slice(0, -1)
    if (ring.length < 3) {
      if (index === 0) return null
      continue
    }
    const counterClockwise = signedArea(ring) > 0
    if (counterClockwise !== (index === 0)) ring.reverse()
    rings.push(ring)
  }
  return rings.length > 0 ? rings : null
}

/**
 * The four corners (counter-clockwise) of the rooftop unit, or null when the building is too small, or the unit
 * would not sit on the roof (an L-shaped footprint, a courtyard). It is aligned with the longest footprint edge.
 */
function rooftopUnit(outer: Ring, holes: Ring[]): Ring | null {
  let ux = 1
  let uy = 0
  let longest = 0
  for (let i = 0; i < outer.length; i += 1) {
    const [ax, ay] = outer[i]!
    const [bx, by] = outer[(i + 1) % outer.length]!
    const length = Math.hypot(bx - ax, by - ay)
    if (length > longest) {
      longest = length
      ux = (bx - ax) / length
      uy = (by - ay) / length
    }
  }
  const vx = -uy
  const vy = ux
  let minU = Infinity
  let maxU = -Infinity
  let minV = Infinity
  let maxV = -Infinity
  for (const [x, y] of outer) {
    const u = x * ux + y * uy
    const v = x * vx + y * vy
    minU = Math.min(minU, u)
    maxU = Math.max(maxU, u)
    minV = Math.min(minV, v)
    maxV = Math.max(maxV, v)
  }
  const length = maxU - minU
  const width = maxV - minV
  if (Math.max(length, width) <= UNIT_MIN_LONG_M || Math.min(length, width) <= UNIT_MIN_SHORT_M)
    return null

  const halfU = (length * UNIT_LENGTH_SHARE) / 2
  const halfV = (width * UNIT_WIDTH_SHARE) / 2
  const centreU = (minU + maxU) / 2
  const centreV = (minV + maxV) / 2
  const corners: Ring = (
    [
      [-1, -1],
      [1, -1],
      [1, 1],
      [-1, 1],
    ] as const
  ).map(([su, sv]) => {
    const u = centreU + su * halfU
    const v = centreV + sv * halfV
    return [u * ux + v * vx, u * uy + v * vy] as Local
  })
  const onRoof = corners.every((p) => inside(p, outer) && holes.every((h) => !inside(p, h)))
  return onRoof ? corners : null
}

/** Even-odd point-in-ring test. */
function inside([x, y]: Local, ring: Ring): boolean {
  let result = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const [xi, yi] = ring[i]!
    const [xj, yj] = ring[j]!
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) result = !result
  }
  return result
}

function toRgb(hex: number): [number, number, number] {
  const c = new Color().setHex(hex)
  return [c.r, c.g, c.b]
}
