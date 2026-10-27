import {
  Box3,
  BoxGeometry,
  CylinderGeometry,
  Group,
  Matrix4,
  Mesh,
  MeshStandardMaterial,
  Quaternion,
  Vector3,
  type BufferGeometry,
} from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'

/** A point in model space: x = length axis, y = up, z = width axis, metres. */
export type Vec3 = [x: number, y: number, z: number]

export interface FitTarget {
  length: number
  width: number
  height: number
}

const materials = new Map<number, MeshStandardMaterial>()

/** One shared matte material per colour: models never get shiny. */
export function matteMaterial(colour: number): MeshStandardMaterial {
  let material = materials.get(colour)
  if (!material) {
    material = new MeshStandardMaterial({ color: colour, roughness: 1, metalness: 0 })
    materials.set(colour, material)
  }
  return material
}

const X_AXIS = new Vector3(1, 0, 0)

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
      new Vector3(1, 1, 1),
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
   * Merges the parts per colour into a group of meshes, then moves and stretches it so its footprint is
   * centred on the origin, it stands on the ground and it measures exactly `target`. The authored shapes are
   * within a few percent already; this makes the declared size and the drawn size the same thing.
   * Also returns the same move for model-space points such as pivots.
   */
  build(target: FitTarget): { group: Group; map: (point: Vec3) => Vec3 } {
    const bounds = new Box3()
    for (const geometries of this.parts.values()) {
      for (const geometry of geometries) {
        geometry.computeBoundingBox()
        bounds.union(geometry.boundingBox!)
      }
    }
    const size = bounds.getSize(new Vector3())
    const scale = new Vector3(target.length / size.x, target.height / size.y, target.width / size.z)
    const centre = bounds.getCenter(new Vector3())
    const fit = new Matrix4()
      .makeScale(scale.x, scale.y, scale.z)
      .multiply(new Matrix4().makeTranslation(-centre.x, -bounds.min.y, -centre.z))

    const group = new Group()
    for (const [colour, geometries] of this.parts) {
      const merged = mergeGeometries(geometries, false)
      geometries.forEach((g) => g.dispose())
      merged.applyMatrix4(fit)
      merged.computeBoundingSphere()
      const mesh = new Mesh(merged, matteMaterial(colour))
      mesh.name = `part-${colour.toString(16).padStart(6, '0')}`
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
