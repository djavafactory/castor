import { InstancedMesh, type Mesh } from 'three'
import type { LodAsset } from './layout-lod'
import { buildLayoutLod, INSTANCE_MIN, LOD_CELL_M, LOD_DISTANCE_M, updateLod } from './layout-lod'

function asset(modelKey: string, x: number, z: number, extra: Partial<LodAsset> = {}): LodAsset {
  return {
    modelKey,
    size: { length: 4, width: 2, height: 2 },
    position: [x, 0, z],
    ...extra,
  }
}

describe('level of detail', () => {
  it('draws detailed models near the camera and plain boxes beyond 1,500 m', () => {
    const lod = buildLayoutLod([asset('FORKLIFT', 0, 0), asset('WAREHOUSE', 0, 10)])
    expect(lod.cells).toHaveLength(1)
    const [cell] = lod.cells

    expect(updateLod(lod, { x: 0, y: 100, z: 0 })).toBe(false)
    expect(cell!.detailed.visible).toBe(true)
    expect(cell!.simple.visible).toBe(false)

    expect(updateLod(lod, { x: LOD_DISTANCE_M + 100, y: 100, z: 0 })).toBe(true)
    expect(cell!.detailed.visible).toBe(false)
    expect(cell!.simple.visible).toBe(true)

    // Coming back restores the detail; nothing changes while the camera stays put
    expect(updateLod(lod, { x: 0, y: 100, z: 0 })).toBe(true)
    expect(updateLod(lod, { x: 0, y: 100, z: 0 })).toBe(false)
    expect(cell!.detailed.visible).toBe(true)
    lod.dispose()
  })

  it('switches each cell on its own distance', () => {
    const far = LOD_CELL_M * 10
    const lod = buildLayoutLod([asset('FORKLIFT', 0, 0), asset('WAREHOUSE', far * 2, 0)])
    expect(lod.cells).toHaveLength(2)
    updateLod(lod, { x: 0, y: 50, z: 0 })
    expect(lod.cells.map((c) => c.detailed.visible)).toEqual([true, false])
    lod.dispose()
  })

  it('draws one coloured box per asset, merged per colour', () => {
    const lod = buildLayoutLod([
      asset('FORKLIFT', 0, 0, { colour: 0xff0000 }),
      asset('FORKLIFT', 10, 0, { colour: 0xff0000 }),
      asset('WAREHOUSE', 20, 0, { colour: 0x00ff00 }),
    ])
    const meshes = lod.cells[0]!.simple.children as Mesh[]
    expect(meshes).toHaveLength(2)
    expect(lod.drawCalls.simple).toBe(2)
    lod.dispose()
  })

  it('keeps the draw calls of a large layout low, fewer still when far', () => {
    const assets = Array.from({ length: 200 }, (_, i) =>
      asset(i % 2 ? 'FORKLIFT' : 'WAREHOUSE', (i % 20) * 40, Math.floor(i / 20) * 40, {
        colour: 0x888888,
        // Every size differs, so nothing is instanced
        size: { length: 4 + i * 0.01, width: 2, height: 2 },
      }),
    )
    const lod = buildLayoutLod(assets)
    expect(lod.drawCalls.detailed).toBeLessThan(100)
    expect(lod.drawCalls.simple).toBeLessThan(lod.drawCalls.detailed)
    lod.dispose()
  })
})

describe('instancing', () => {
  it('draws a model repeated often enough through InstancedMesh, one mesh per material', () => {
    const copies = Array.from({ length: INSTANCE_MIN }, (_, i) => asset('FORKLIFT', i * 30, 0))
    const lod = buildLayoutLod(copies)
    expect(lod.instancedAssets).toBe(INSTANCE_MIN)
    expect(lod.cells).toHaveLength(0)
    const instanced: InstancedMesh[] = []
    lod.group.traverse((o) => {
      if (o instanceof InstancedMesh) instanced.push(o)
    })
    expect(instanced.length).toBe(lod.instancedMeshes)
    expect(instanced.every((m) => m.count === INSTANCE_MIN)).toBe(true)
    // The copies share one geometry per material
    expect(new Set(instanced.map((m) => m.geometry)).size).toBe(instanced.length)
    lod.dispose()
  })

  it('merges a model repeated too rarely, or with different sizes, instead', () => {
    const lod = buildLayoutLod([
      asset('FORKLIFT', 0, 0),
      asset('FORKLIFT', 10, 0),
      asset('FORKLIFT', 20, 0, { size: { length: 5, width: 2, height: 2 } }),
    ])
    expect(lod.instancedAssets).toBe(0)
    expect(lod.cells).toHaveLength(1)
    lod.dispose()
  })

  it('never instances an asset with its own footprint', () => {
    const ring: [number, number][] = [
      [-5, -5],
      [5, -5],
      [5, 5],
      [-5, 5],
    ]
    const lod = buildLayoutLod(
      Array.from({ length: 4 }, (_, i) => asset('WAREHOUSE', i * 50, 0, { footprint: ring })),
    )
    expect(lod.instancedAssets).toBe(0)
    lod.dispose()
  })

  it('reports model keys the library does not know', () => {
    const lod = buildLayoutLod([asset('NOT_A_MODEL', 0, 0)])
    expect(lod.unknown).toEqual(['NOT_A_MODEL'])
    lod.dispose()
  })
})
