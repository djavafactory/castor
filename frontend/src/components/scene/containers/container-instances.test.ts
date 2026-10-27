import {
  InstancedMesh,
  Matrix4,
  Quaternion,
  Vector3,
  type Mesh,
  type MeshStandardMaterial,
} from 'three'
import { describe, expect, it } from 'vitest'
import { buildYard, disposeYard } from './container-instances'
import { PAD_COLOUR, PAD_TOP, drawnSize } from './container-stacks'

const YARD = { code: 'CY-1', length: 200, width: 80, maxTiers: 4, designFill: 0.6 }

describe('buildYard', () => {
  it('draws the pad and one InstancedMesh per box size, with frustum culling on', () => {
    const { group, boxes } = buildYard(YARD)
    const meshes = group.children.filter((c): c is InstancedMesh => c instanceof InstancedMesh)
    expect(meshes.map((m) => m.name).sort()).toEqual(['containers-20', 'containers-40'])
    expect(meshes[0].count + meshes[1].count).toBe(boxes.length)
    for (const mesh of meshes) {
      expect(mesh.frustumCulled).toBe(true)
      expect(mesh.boundingSphere?.radius).toBeGreaterThan(0)
    }
    const pad = group.getObjectByName('pad') as Mesh
    expect((pad.material as MeshStandardMaterial).color.getHex()).toBe(PAD_COLOUR)
  })

  it('puts every instance where its box is, drawn 0.12 m shorter and narrower', () => {
    const { group, boxes } = buildYard(YARD)
    const mesh = group.getObjectByName('containers-20') as InstancedMesh
    const [l, h, w] = drawnSize(20)
    expect(l).toBeCloseTo(5.938)
    expect(w).toBeCloseTo(2.471)
    const matrix = new Matrix4()
    const position = new Vector3()
    const scale = new Vector3()
    boxes
      .filter((b) => b.size === 20)
      .forEach((box, i) => {
        mesh.getMatrixAt(i, matrix)
        matrix.decompose(position, new Quaternion(), scale)
        expect(position.x).toBeCloseTo(box.x, 4)
        expect(position.y).toBeCloseTo(box.y, 4)
        expect(position.z).toBeCloseTo(box.z, 4)
        expect(scale.x).toBeCloseTo(l, 4)
        expect(scale.y).toBeCloseTo(h, 4)
        expect(scale.z).toBeCloseTo(w, 4)
        expect(position.y - scale.y / 2).toBeGreaterThanOrEqual(PAD_TOP - 1e-6)
      })
  })

  it('draws only the pad of a yard without boxes, and frees without error', () => {
    const built = buildYard({ ...YARD, length: 5 })
    expect(built.group.children.map((c) => c.name)).toEqual(['pad'])
    expect(() => disposeYard(built.group)).not.toThrow()
  })
})
