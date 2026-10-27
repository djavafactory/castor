import { Box3, Mesh, MeshStandardMaterial, Vector3, type Group } from 'three'
import type { Pt } from './box-batch'
import { buildLayoutMeshes, type LayoutAsset } from './layout-batch'
import type { ModelDetails, ModelParams, ModelSize } from './model-spec'
import { MODEL_SPECS, buildModel, defaultParams, getModelSpec, modelScale } from './registry'

const PART2 = MODEL_SPECS.filter((s) => s.parametric)

function params(key: string, over: Partial<ModelParams> = {}): ModelParams {
  return { ...defaultParams(getModelSpec(key)!), ...over }
}

function build(key: string, over: Partial<ModelParams> = {}) {
  return buildModel(getModelSpec(key)!, params(key, over)).group
}

function colours(group: Group): string[] {
  return group.children.map((m) => m.name.replace('part-', ''))
}

function vertices(group: Group, colour?: string): Vector3[] {
  const out: Vector3[] = []
  for (const child of group.children) {
    if (colour && child.name !== `part-${colour}`) continue
    const position = (child as Mesh).geometry.getAttribute('position')
    for (let i = 0; i < position.count; i++)
      out.push(new Vector3().fromBufferAttribute(position, i))
  }
  return out
}

/** Pieces of one colour, counted by their bounding boxes along x (every box of a mesh is separate). */
function boxesOf(group: Group, colour: string): number {
  const mesh = group.children.find((m) => m.name === `part-${colour}`) as Mesh | undefined
  return mesh ? mesh.geometry.getAttribute('position').count / 36 : 0
}

describe('part 2 models', () => {
  it('are all parametric: drawn true to the asset, never scaled', () => {
    expect(PART2).toHaveLength(37)
    for (const spec of PART2) {
      expect(modelScale(spec, { length: 3, width: 4, height: 5 })).toEqual([1, 1, 1])
    }
  })

  describe.each(PART2.map((s) => [s.key, s] as const))('%s', (_key, spec) => {
    const group = buildModel(spec).group
    const box = new Box3().setFromObject(group)
    const size = box.getSize(new Vector3())

    it('renders: a few meshes, no broken geometry', () => {
      expect(group.children.length).toBeGreaterThan(0)
      expect(group.children.length).toBeLessThanOrEqual(8)
      for (const v of vertices(group)) expect(Number.isFinite(v.x + v.y + v.z)).toBe(true)
    })

    // The berth is only the strip and rails along its quay edge, the fender only its blocks
    const strip = ['BERTH', 'FENDER'].includes(spec.key)

    it('shows its footprint and height, standing on the ground and centred', () => {
      // Strokes drawn along an outline reach half their width past it
      expect(size.x).toBeLessThanOrEqual(spec.size.length + 1)
      expect(size.z).toBeLessThanOrEqual(spec.size.width + 1)
      if (!strip) {
        expect(size.x).toBeGreaterThanOrEqual(spec.size.length - 1)
        expect(size.z).toBeGreaterThanOrEqual(spec.size.width - 1)
      }
      expect(size.y).toBeLessThanOrEqual(spec.size.height + 1e-3)
      expect(size.y).toBeGreaterThanOrEqual(spec.size.height * 0.75)
      expect(box.min.y).toBeGreaterThanOrEqual(-1e-6)
      if (strip) {
        expect(box.min.x).toBeGreaterThanOrEqual(-spec.size.length / 2 - 1e-6)
        expect(box.max.z).toBeLessThanOrEqual(spec.size.width / 2 + 1e-6)
      } else {
        expect(Math.abs((box.min.x + box.max.x) / 2)).toBeLessThan(1.5)
        expect(Math.abs((box.min.z + box.max.z) / 2)).toBeLessThan(1.5)
      }
    })

    it('is matte', () => {
      for (const mesh of group.children as Mesh[]) {
        const material = mesh.material as MeshStandardMaterial
        expect(material.roughness).toBe(1)
        expect(material.metalness).toBe(0)
      }
    })
  })
})

describe('buildings', () => {
  it('extrudes the drawn footprint: an L stays an L', () => {
    const walls = vertices(build('WAREHOUSE_POLY'), 'c9bfa3')
    expect(walls.length).toBeGreaterThan(0)
    // The L cuts out x 0..30, z 0..20: no wall vertex inside it
    expect(walls.some((v) => v.x > 0.01 && v.z > 0.01)).toBe(false)
    expect(Math.max(...walls.map((v) => v.y))).toBeCloseTo(9.6)
  })

  it('paints walls sand and roof and floor brown', () => {
    expect(colours(build('WAREHOUSE_POLY')).sort()).toEqual(['8c7a5a', 'c9bfa3'])
    expect(colours(build('WAREHOUSE'))).toEqual(
      expect.arrayContaining(['c9bfa3', '8c7a5a', '2b3442']),
    )
  })

  it('draws as many dock doors as the details say, on one side', () => {
    for (const doors of [2, 5]) {
      const group = build('WAREHOUSE', { details: { doors, roofRise: 3 } })
      expect(boxesOf(group, '2b3442')).toBe(doors)
      const zs = vertices(group, '2b3442').map((v) => v.z)
      expect(Math.min(...zs)).toBeGreaterThan(0)
    }
  })

  it('puts a door every 12 m on the transit shed and raises the gabled roof', () => {
    const shed = build('TRANSIT_SHED')
    expect(boxesOf(shed, '2b3442')).toBe(Math.floor((84 - 6) / 12) + 1)
    const roof = new Box3().setFromObject(shed)
    expect(roof.max.y).toBeCloseTo(9)
  })
})

describe('storage', () => {
  it('draws the slot grid and as many piles as the design fill says', () => {
    const half = build('STORAGE_PAD', {
      details: { bays: 4, rows: 2, designFill: 0.5, cargoForm: 'PILE' },
    })
    const full = build('STORAGE_PAD', {
      details: { bays: 4, rows: 2, designFill: 1, cargoForm: 'PILE' },
    })
    const empty = build('STORAGE_PAD', { details: { bays: 4, rows: 2, designFill: 0 } })
    expect(colours(empty)).not.toContain('8a6d4b')
    expect(vertices(full, '8a6d4b').length).toBe(2 * vertices(half, '8a6d4b').length)
    expect(boxesOf(full, 'ffffff')).toBe(5 + 3) // painted lines: bays + 1 and rows + 1
  })

  it('shapes a pile frustum 0.55 x 0.15 and a stack 0.92 x 0.92 at the pad height', () => {
    const pile = build('STORAGE_PAD', {
      details: { bays: 1, rows: 1, designFill: 1, cargoForm: 'PILE' },
    })
    const top = vertices(pile, '8a6d4b').filter((v) => v.y > 7.9)
    const xs = top.map((v) => v.x)
    const zs = top.map((v) => v.z)
    expect(Math.max(...xs) - Math.min(...xs)).toBeCloseTo(58 * 0.55, 1)
    expect(Math.max(...zs) - Math.min(...zs)).toBeCloseTo(38 * 0.15, 1)
    const stack = build('STORAGE_PAD', {
      details: { bays: 1, rows: 1, designFill: 1, cargoForm: 'STACK' },
    })
    const sx = vertices(stack, '5d7f9a')
      .filter((v) => v.y > 7.9)
      .map((v) => v.x)
    expect(Math.max(...sx) - Math.min(...sx)).toBeCloseTo(58.5 * 0.92, 1)
  })

  it('draws silo cargo as a cylinder D = min(L, W), twice the maximum height', () => {
    const group = build('STORAGE_PAD', {
      details: { bays: 1, rows: 1, designFill: 1, cargoForm: 'SILO' },
    })
    const silo = vertices(group, 'c9cdd2')
    expect(Math.max(...silo.map((v) => v.y))).toBeCloseTo(0.2 + 16)
    expect(Math.max(...silo.map((v) => Math.hypot(v.x, v.z)))).toBeCloseTo(19.5, 1)
  })

  it('captions the pad 3 m above its maximum height', () => {
    const caption = getModelSpec('STORAGE_PAD')!.caption!(
      params('STORAGE_PAD', { details: { name: 'Pad C' } }),
    )
    expect(caption).toEqual({ text: 'Pad C', height: 11 })
  })

  it('lays coils on a 3.4 / 3.2 m grid by design fill, only inside the drawn outline', () => {
    const l: Pt[] = [
      [-25, -15],
      [25, -15],
      [25, 0],
      [0, 0],
      [0, 15],
      [-25, 15],
    ]
    const full = build('LAYDOWN_YARD', { footprint: l, details: { designFill: 1 } })
    const coils = vertices(full, 'b8c0c8')
    expect(coils.length).toBeGreaterThan(0)
    expect(coils.some((v) => v.x > 0 && v.z > 0)).toBe(false)
    const half = build('LAYDOWN_YARD', { footprint: l, details: { designFill: 0.5 } })
    expect(Math.abs(vertices(half, 'b8c0c8').length / coils.length - 0.5)).toBeLessThan(0.02)
    expect(colours(build('QUAY_STAGING', { details: { designFill: 0 } }))).not.toContain('b8c0c8')
  })

  it('walls a stockpile bay on three sides at 3.6 m', () => {
    const walls = vertices(build('STOCKPILE_BAY'), 'b9b4a8')
    expect(Math.max(...walls.map((v) => v.y))).toBeCloseTo(3.6)
    expect(boxesOf(build('STOCKPILE_BAY'), 'b9b4a8')).toBe(3)
  })
})

describe('bulk and liquid', () => {
  it('colours silo, hopper, tank and gauges', () => {
    expect(colours(build('SILO'))).toEqual(expect.arrayContaining(['c9cdd2', 'e2be5a', '2b3442']))
    expect(colours(build('HOPPER'))).toEqual(expect.arrayContaining(['e8a317', '6b7480']))
    expect(colours(build('LIQUID_TANK'))).toEqual(expect.arrayContaining(['b9c4cc', '3c6fb4']))
  })

  it('stands the silo at D = min(L, W) and fills its gauge by design fill', () => {
    const group = build('SILO', {
      size: { length: 14, width: 20, height: 30 },
      details: { designFill: 0.5 },
    })
    const gauge = vertices(group, 'e2be5a')
    expect(Math.max(...gauge.map((v) => v.y))).toBeCloseTo(0.5 * (30 - 14 * 0.18))
    expect(new Box3().setFromObject(group).getSize(new Vector3()).z).toBeLessThanOrEqual(14)
  })

  it('stands the conveyor belt at height on trestles every 10 m', () => {
    const group = build('CONVEYOR')
    expect(boxesOf(group, '2b3442')).toBe(1)
    const belt = vertices(group, '2b3442')
    expect(Math.max(...belt.map((v) => v.y))).toBeCloseTo(5.6)
    // 9 stations on 80 m, two legs and a cross member each
    expect(boxesOf(group, '6b7480')).toBe(2 + 3 * 9)
  })

  it('follows a bent conveyor path', () => {
    const path: Pt[] = [
      [-20, -10],
      [0, -10],
      [0, 10],
    ]
    const box = new Box3().setFromObject(build('CONVEYOR', { path }))
    expect(box.max.z).toBeGreaterThan(9)
    expect(box.min.x).toBeLessThan(-19)
  })

  it('draws the bund as walls along the drawn polygon plus a floor, and tank bunds apart from tanks', () => {
    const ring: Pt[] = [
      [-10, -10],
      [10, -10],
      [0, 10],
    ]
    const group = build('BUND_WALL', { footprint: ring })
    expect(colours(group)).toEqual(['b9b4a8'])
    expect(Math.max(...vertices(group).map((v) => v.y))).toBeCloseTo(2)
    expect(colours(build('LIQUID_TANK'))).not.toContain('b9b4a8')
  })

  it('draws red pipes on supports every 8 m and a rack of three coloured pipes', () => {
    const line = build('PIPELINE')
    expect(colours(line)).toEqual(expect.arrayContaining(['c8423b', '6b7480']))
    expect(boxesOf(line, '6b7480')).toBe(9)
    expect(colours(build('PIPE_RACK'))).toEqual(
      expect.arrayContaining(['c8423b', '3c6fb4', 'e8a317']),
    )
  })
})

describe('marine', () => {
  it('draws the amber edge strip 1.2 m wide on the water side', () => {
    const strip = vertices(build('BERTH'), 'e8a317')
    expect(Math.min(...strip.map((v) => v.z))).toBeCloseTo(-15)
    expect(Math.max(...strip.map((v) => v.z))).toBeCloseTo(-13.8)
  })

  it('draws crane rails only on berths with rail quay cranes', () => {
    expect(colours(build('BERTH', { details: { railCranes: true } }))).toContain('2b3442')
    expect(colours(build('BERTH', { details: { railCranes: false } }))).not.toContain('2b3442')
    const rails = vertices(build('BERTH', { details: { railCranes: true } }), '2b3442')
    const zs = [...new Set(rails.map((v) => Math.round(v.z)))]
    expect(Math.max(...zs) - Math.min(...zs)).toBeGreaterThanOrEqual(16)
  })

  it('labels the berth with name, length and depth', () => {
    const caption = getModelSpec('BERTH')!.caption!(
      params('BERTH', {
        size: { length: 250, width: 30, height: 0.2 },
        details: { name: 'Berth 3', depth: 15 },
      }),
    )
    expect(caption?.text).toBe('Berth 3  250 m / 15 m deep')
  })

  it('puts fender blocks every 6 m from 3 m', () => {
    expect(boxesOf(build('FENDER'), '2b3442')).toBe(10)
    const xs = vertices(build('FENDER'), '2b3442').map((v) => v.x)
    expect(Math.min(...xs)).toBeCloseTo(-30 + 3 - 0.5)
  })

  it('draws the RoRo ramp as a grey wedge', () => {
    const ramp = vertices(build('RORO_RAMP'), '8c949e')
    expect(Math.max(...ramp.map((v) => v.y))).toBeCloseTo(3)
    expect(Math.min(...ramp.filter((v) => v.x < -6.9).map((v) => v.y))).toBeCloseTo(0)
    expect(Math.max(...ramp.filter((v) => v.x < -6.9).map((v) => v.y))).toBeCloseTo(0)
  })
})

describe('gates, roads and parking', () => {
  it('draws the gate with booths, canopy and a red barrier arm', () => {
    expect(colours(build('GATE'))).toEqual(expect.arrayContaining(['e9e5dc', '8c7a5a', 'b5382f']))
  })

  it('draws no parked trucks on the holding area', () => {
    const group = build('TRUCK_HOLDING')
    expect(Math.max(...vertices(group).map((v) => v.y))).toBeLessThan(0.2)
  })

  it('draws bay lines every 2.6 m and as many cars as the design fill says', () => {
    const none = build('PARKING_YARD', { details: { designFill: 0 } })
    const full = build('PARKING_YARD', { details: { designFill: 1 } })
    expect(colours(none)).not.toContain('b5382f')
    const bays = Math.floor(40 / 2.6)
    expect(boxesOf(full, 'ffffff')).toBeGreaterThanOrEqual((bays + 1) * 3)
    expect(Math.max(...vertices(full).map((v) => v.y))).toBeLessThanOrEqual(1.6)
  })

  it('roofs the inspection bay in amber', () => {
    expect(colours(build('INSPECTION_BAY'))).toContain('e8a317')
  })

  it('draws the three lane types as ribbons along a bent path', () => {
    const path: Pt[] = [
      [-20, 0],
      [0, 0],
      [0, 20],
    ]
    for (const key of ['DISPATCH_LANE', 'FIRE_ACCESS', 'INTERNAL_ROAD']) {
      const box = new Box3().setFromObject(build(key, { path }))
      expect(box.max.z).toBeGreaterThan(19)
      expect(box.min.x).toBeLessThan(-19)
    }
  })
})

describe('zones', () => {
  it('fills the exclusion zone 20 % translucent with red corner posts 1.8 m', () => {
    const group = build('EXCLUSION_ZONE')
    const fill = group.children.find((m) => m.name === 'part-c8423b-20') as Mesh
    const material = fill.material as MeshStandardMaterial
    expect(material.transparent).toBe(true)
    expect(material.opacity).toBeCloseTo(0.2)
    expect(material.depthWrite).toBe(false)
    expect(new Box3().setFromObject(group).max.y).toBeCloseTo(1.8)
    expect(boxesOf(group, 'c8423b')).toBeGreaterThanOrEqual(4)
  })

  it('draws the drainage zone 35 % translucent with a dashed outline', () => {
    const group = build('DRAINAGE_ZONE')
    const material = (group.children.find((m) => m.name === 'part-6fa8c9-35') as Mesh)
      .material as MeshStandardMaterial
    expect(material.opacity).toBeCloseTo(0.35)
    expect(boxesOf(group, '6fa8c9')).toBeGreaterThan(20)
  })

  it('puts fence posts every 4 m and rails at 45 % and 85 % of the height', () => {
    const group = build('FENCE')
    expect(boxesOf(group, '6b7480')).toBe(21)
    const rails = vertices(group, '2b3442')
    const levels = [...new Set(rails.map((v) => Math.round(v.y * 100) / 100))]
    expect(levels.some((y) => Math.abs(y - (2.4 * 0.45 + 0.04)) < 0.02)).toBe(true)
    expect(levels.some((y) => Math.abs(y - (2.4 * 0.85 + 0.04)) < 0.02)).toBe(true)
  })

  it('draws the terminal boundary as an outline and the surface area as a fill', () => {
    const boundary = build('TERMINAL_BOUNDARY')
    expect(vertices(boundary).some((v) => Math.abs(v.x) < 90 && Math.abs(v.z) < 50)).toBe(false)
    expect(colours(build('SURFACE_AREA'))).toEqual(['d8d4c8'])
  })
})

describe('layout draw calls', () => {
  const SIZES: Record<string, ModelSize> = {}
  for (const spec of MODEL_SPECS) SIZES[spec.key] = spec.size

  /** A Westports-sized layout: every asset type placed several times, some 340 assets. */
  function layout(copies: number): LayoutAsset[] {
    const assets: LayoutAsset[] = []
    MODEL_SPECS.forEach((spec, i) => {
      for (let c = 0; c < copies; c++) {
        const details: ModelDetails = { ...spec.details, name: `${spec.label} ${c}` }
        assets.push({
          modelKey: spec.key,
          size: spec.size,
          footprint: spec.footprint,
          path: spec.path,
          details,
          position: [(i % 8) * 220, 0, Math.floor(i / 8) * 220 + c * 60],
          rotationY: (c * Math.PI) / 7,
        })
      }
    })
    return assets
  }

  it('walks the registry: every one of the 56 types merges into the layout', () => {
    const built = buildLayoutMeshes(layout(1))
    expect(built.unknown).toEqual([])
    expect(MODEL_SPECS).toHaveLength(56)
    expect(built.group.children.length).toBeGreaterThan(0)
  })

  it('keeps a full layout far below 400 draw calls', () => {
    const assets = layout(6)
    expect(assets.length).toBe(336)
    const built = buildLayoutMeshes(assets)
    expect(built.drawCalls).toBeLessThan(400)
    expect(built.drawCalls).toBeLessThan(120)
  })

  it('reports keys it does not know and draws nothing for them', () => {
    const built = buildLayoutMeshes([
      { modelKey: 'NOPE', size: { length: 1, width: 1, height: 1 }, position: [0, 0, 0] },
    ])
    expect(built.unknown).toEqual(['NOPE'])
    expect(built.group.children).toHaveLength(0)
  })

  it('places and turns each asset', () => {
    const built = buildLayoutMeshes([
      {
        modelKey: 'BUILDING',
        size: { length: 20, width: 12, height: 8 },
        position: [100, 5, 50],
        rotationY: Math.PI / 2,
      },
    ])
    const box = new Box3().setFromObject(built.group)
    expect(box.min.y).toBeCloseTo(5)
    expect(box.getCenter(new Vector3()).x).toBeCloseTo(100)
    // Turned a quarter: the 20 m length now runs along z
    expect(box.getSize(new Vector3()).z).toBeCloseTo(20)
  })
})
