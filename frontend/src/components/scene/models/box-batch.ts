import {
  Box3,
  BoxGeometry,
  CylinderGeometry,
  ExtrudeGeometry,
  Group,
  Matrix4,
  Mesh,
  MeshStandardMaterial,
  Quaternion,
  Shape,
  SphereGeometry,
  Vector2,
  Vector3,
  type BufferGeometry,
} from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'

/** A point in model space: x = length axis, y = up, z = width axis, metres. */
export type Vec3 = [x: number, y: number, z: number]

/** A point on the ground plane of model space: x = length axis, z = width axis, metres. */
export type Pt = [x: number, z: number]

/** A colour with a see-through percentage (1..99) packed into the key: translucent zone fills. */
export function tint(colour: number, percent: number): number {
  return percent * 0x1000000 + colour
}

const colourOf = (key: number) => key & 0xffffff
const percentOf = (key: number) => key >>> 24

export interface FitTarget {
  length: number
  width: number
  height: number
}

const materials = new Map<number, MeshStandardMaterial>()

/** One shared matte material per colour (and see-through percentage): models never get shiny. */
export function matteMaterial(key: number): MeshStandardMaterial {
  let material = materials.get(key)
  if (!material) {
    const percent = percentOf(key)
    material = new MeshStandardMaterial({
      color: colourOf(key),
      roughness: 1,
      metalness: 0,
      ...(percent > 0 && { transparent: true, opacity: percent / 100, depthWrite: false }),
    })
    materials.set(key, material)
  }
  return material
}

/** Name of the mesh of a colour key: `part-rrggbb`, with `-<percent>` when translucent. */
export function partName(key: number): string {
  const percent = percentOf(key)
  return `part-${colourOf(key).toString(16).padStart(6, '0')}${percent > 0 ? `-${percent}` : ''}`
}

const X_AXIS = new Vector3(1, 0, 0)
const Y_AXIS = new Vector3(0, 1, 0)
const UNIT = new Vector3(1, 1, 1)

/**
 * Collects boxes, cylinders and beams and merges them per colour: a model is one mesh, so one draw call,
 * per colour it uses.
 */
export class BoxBatch {
  private readonly parts = new Map<number, BufferGeometry[]>()

  get colourCount(): number {
    return this.parts.size
  }

  /** An axis-aligned box from its minimum and maximum corner. */
  block(colour: number, x0: number, x1: number, y0: number, y1: number, z0: number, z1: number) {
    const geometry = new BoxGeometry(x1 - x0, y1 - y0, z1 - z0)
    this.add(
      colour,
      geometry,
      new Matrix4().makeTranslation((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2),
    )
  }

  /** A low-poly cylinder (or cone frustum) centred on (cx, cy, cz), its axis along x, y or z. */
  cylinder(
    colour: number,
    axis: 'x' | 'y' | 'z',
    [cx, cy, cz]: Vec3,
    radius: number,
    length: number,
    segments = 10,
  ) {
    const geometry = new CylinderGeometry(radius, radius, length, segments)
    if (axis === 'x') geometry.rotateZ(-Math.PI / 2)
    if (axis === 'z') geometry.rotateX(Math.PI / 2)
    this.add(colour, geometry, new Matrix4().makeTranslation(cx, cy, cz))
  }

  /** A cone frustum standing on y, centred on (cx, cy, cz): wider at the bottom when rBottom > rTop. */
  frustum(
    colour: number,
    [cx, cy, cz]: Vec3,
    rBottom: number,
    rTop: number,
    height: number,
    segments = 12,
  ) {
    const geometry = new CylinderGeometry(rTop, rBottom, height, segments)
    this.add(colour, geometry, new Matrix4().makeTranslation(cx, cy, cz))
  }

  /** A cylinder between two points: a pipe along a path. */
  tube(colour: number, from: Vec3, to: Vec3, radius: number, segments = 8) {
    const a = new Vector3(...from)
    const b = new Vector3(...to)
    const direction = b.clone().sub(a)
    const geometry = new CylinderGeometry(radius, radius, direction.length(), segments)
    const matrix = new Matrix4().compose(
      a.clone().add(b).multiplyScalar(0.5),
      new Quaternion().setFromUnitVectors(Y_AXIS, direction.normalize()),
      UNIT,
    )
    this.add(colour, geometry, matrix)
  }

  /** The top half of a sphere squashed to `rise`, sitting on y = cy: silo domes. */
  dome(colour: number, [cx, cy, cz]: Vec3, radius: number, rise: number, segments = 14) {
    const geometry = new SphereGeometry(radius, segments, 5, 0, Math.PI * 2, 0, Math.PI / 2)
    geometry.scale(1, rise / radius, 1)
    this.add(colour, geometry, new Matrix4().makeTranslation(cx, cy, cz))
  }

  /** A rectangular frustum on y0: base `bl` x `bw`, the top that fraction of it. Piles and stacks. */
  taper(
    colour: number,
    [cx, y0, cz]: Vec3,
    bl: number,
    bw: number,
    height: number,
    topL: number,
    topW: number,
  ) {
    const geometry = new CylinderGeometry(Math.SQRT1_2, Math.SQRT1_2, 1, 4).toNonIndexed()
    geometry.rotateY(Math.PI / 4)
    const position = geometry.getAttribute('position')
    for (let i = 0; i < position.count; i++) {
      const top = position.getY(i) > 0
      position.setXYZ(
        i,
        position.getX(i) * bl * (top ? topL : 1),
        position.getY(i) * height,
        position.getZ(i) * bw * (top ? topW : 1),
      )
    }
    geometry.computeVertexNormals()
    this.add(colour, geometry, new Matrix4().makeTranslation(cx, y0 + height / 2, cz))
  }

  /** A wedge rising along +X from y0 at x0 to y1 at x1. */
  wedge(colour: number, x0: number, x1: number, y0: number, y1: number, z0: number, z1: number) {
    const shape = new Shape([new Vector2(x0, y0), new Vector2(x1, y0), new Vector2(x1, y1)])
    const geometry = new ExtrudeGeometry(shape, { depth: z1 - z0, bevelEnabled: false })
    this.add(colour, geometry, new Matrix4().makeTranslation(0, 0, z0))
  }

  /** A gabled roof: eaves at y0 over z0..z1, the ridge along X at y0 + rise. */
  gable(colour: number, x0: number, x1: number, z0: number, z1: number, y0: number, rise: number) {
    // The turn about y below sends shape x to -z and the extrusion to +x
    const shape = new Shape([
      new Vector2(-z0, y0),
      new Vector2(-z1, y0),
      new Vector2(-(z0 + z1) / 2, y0 + rise),
    ])
    const geometry = new ExtrudeGeometry(shape, { depth: x1 - x0, bevelEnabled: false })
    geometry.rotateY(Math.PI / 2)
    this.add(colour, geometry, new Matrix4().makeTranslation(x0, 0, 0))
  }

  /** A polygon on the ground plane lifted to a height: floors, walls of a drawn footprint, zone fills. */
  prism(colour: number, ring: Pt[], y0: number, y1: number) {
    // The turn that stands the extrusion up sends shape y to -z
    const shape = new Shape(ring.map(([x, z]) => new Vector2(x, -z)))
    const geometry = new ExtrudeGeometry(shape, { depth: y1 - y0, bevelEnabled: false })
    geometry.rotateX(-Math.PI / 2)
    this.add(colour, geometry, new Matrix4().makeTranslation(0, y0, 0))
  }

  /** A box laid between two ground points, `width` across: a piece of road, rail or wall. */
  slab(colour: number, [ax, az]: Pt, [bx, bz]: Pt, width: number, y0: number, y1: number) {
    const dx = bx - ax
    const dz = bz - az
    const length = Math.hypot(dx, dz)
    if (length < 1e-6) return
    const geometry = new BoxGeometry(length, y1 - y0, width)
    this.add(
      colour,
      geometry,
      new Matrix4().compose(
        new Vector3((ax + bx) / 2, (y0 + y1) / 2, (az + bz) / 2),
        new Quaternion().setFromAxisAngle(Y_AXIS, -Math.atan2(dz, dx)),
        UNIT,
      ),
    )
  }

  /** A ribbon along a polyline (closed: back to the start), rounded at the corners. */
  ribbon(colour: number, points: Pt[], width: number, y0: number, y1: number, closed = false) {
    const last = closed ? points.length : points.length - 1
    for (let i = 0; i < last; i++) {
      this.slab(colour, points[i]!, points[(i + 1) % points.length]!, width, y0, y1)
    }
    const joints = closed ? points : points.slice(1, -1)
    for (const [x, z] of joints) {
      this.cylinder(colour, 'y', [x, (y0 + y1) / 2, z], width / 2, y1 - y0, 8)
    }
  }

  /** Dashes along a polyline: drainage outlines, lane centre lines. */
  dashes(
    colour: number,
    points: Pt[],
    width: number,
    y0: number,
    y1: number,
    dash: number,
    gap: number,
    closed = false,
  ) {
    const last = closed ? points.length : points.length - 1
    let next = 0 // distance into the current segment where the next dash starts
    for (let i = 0; i < last; i++) {
      const [ax, az] = points[i]!
      const [bx, bz] = points[(i + 1) % points.length]!
      const length = Math.hypot(bx - ax, bz - az)
      const at = (t: number): Pt => [ax + ((bx - ax) * t) / length, az + ((bz - az) * t) / length]
      let d = next
      for (; d < length; d += dash + gap) {
        this.slab(colour, at(d), at(Math.min(length, d + dash)), width, y0, y1)
      }
      next = d - length
    }
  }

  /** A square-section bar between two points: inclined legs, booms and struts. */
  beam(colour: number, from: Vec3, to: Vec3, thickness: number) {
    const a = new Vector3(...from)
    const direction = new Vector3(...to).sub(a)
    const geometry = new BoxGeometry(direction.length(), thickness, thickness)
    const matrix = new Matrix4().compose(
      a
        .clone()
        .add(new Vector3(...to))
        .multiplyScalar(0.5),
      new Quaternion().setFromUnitVectors(X_AXIS, direction.normalize()),
      UNIT,
    )
    this.add(colour, geometry, matrix)
  }

  /**
   * A lattice boom: four chords and cross rungs. `across` is a horizontal unit vector at right angles
   * to the boom, `width` the distance between the chords.
   */
  lattice(colour: number, from: Vec3, to: Vec3, across: Vec3, width: number, thickness = 0.35) {
    const a = new Vector3(...from)
    const b = new Vector3(...to)
    const along = b.clone().sub(a)
    const side = new Vector3(...across).multiplyScalar(width / 2)
    const lift = along
      .clone()
      .cross(side)
      .normalize()
      .multiplyScalar(width / 2)
    const corners = [
      side.clone().add(lift),
      side.clone().sub(lift),
      side.clone().negate().add(lift),
      side.clone().negate().sub(lift),
    ]
    for (const c of corners) {
      this.beam(colour, a.clone().add(c).toArray(), b.clone().add(c).toArray(), thickness)
    }
    const rungs = Math.max(2, Math.round(along.length() / 4))
    for (let i = 0; i <= rungs; i++) {
      const p = a.clone().add(along.clone().multiplyScalar(i / rungs))
      this.beam(
        colour,
        p.clone().add(side).toArray(),
        p.clone().sub(side).toArray(),
        thickness * 0.7,
      )
    }
  }

  /**
   * Merges the parts per colour into a group of meshes. With a `target`, moves and stretches it so its
   * footprint is centred on the origin, it stands on the ground and it measures exactly `target`: the authored
   * shapes are within a few percent already; this makes the declared size and the drawn size the same thing.
   * Without one the parts stay exactly where they were authored (parametric models are drawn true to size).
   * Also returns the same move for model-space points such as pivots.
   */
  build(target: FitTarget | null): { group: Group; map: (point: Vec3) => Vec3 } {
    let fit = new Matrix4()
    if (target) {
      const bounds = new Box3()
      for (const geometries of this.parts.values()) {
        for (const geometry of geometries) {
          geometry.computeBoundingBox()
          bounds.union(geometry.boundingBox!)
        }
      }
      const size = bounds.getSize(new Vector3())
      const scale = new Vector3(
        target.length / size.x,
        target.height / size.y,
        target.width / size.z,
      )
      const centre = bounds.getCenter(new Vector3())
      fit = new Matrix4()
        .makeScale(scale.x, scale.y, scale.z)
        .multiply(new Matrix4().makeTranslation(-centre.x, -bounds.min.y, -centre.z))
    }

    const group = new Group()
    for (const [colour, geometries] of this.parts) {
      const merged = mergeGeometries(geometries, false)
      geometries.forEach((g) => g.dispose())
      merged.applyMatrix4(fit)
      merged.computeBoundingSphere()
      const mesh = new Mesh(merged, matteMaterial(colour))
      mesh.name = partName(colour)
      mesh.renderOrder = percentOf(colour) > 0 ? 1 : 0
      group.add(mesh)
    }
    this.parts.clear()
    return { group, map: (p) => new Vector3(...p).applyMatrix4(fit).toArray() }
  }

  private add(colour: number, geometry: BufferGeometry, matrix: Matrix4) {
    geometry.applyMatrix4(matrix)
    const flat = geometry.index ? geometry.toNonIndexed() : geometry
    if (flat !== geometry) geometry.dispose()
    flat.deleteAttribute('uv')
    const list = this.parts.get(colour)
    if (list) list.push(flat)
    else this.parts.set(colour, [flat])
  }
}
