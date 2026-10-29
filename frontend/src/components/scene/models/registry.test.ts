import { Box3, Vector3 } from 'three'
import { MODEL_SPECS, buildModel, getModelSpec, modelEnvelopeSize, modelScale } from './registry'
import { tallest } from './model-spec'

/** Part 2: structures, areas, roads and zones. Their keys, once. */
const PART2_KEYS = [
  'WAREHOUSE_POLY',
  'WAREHOUSE',
  'TRANSIT_SHED',
  'COVERED_BAY',
  'BUILDING',
  'STORAGE_PAD',
  'LAYDOWN_YARD',
  'QUAY_STAGING',
  'STOCKPILE_BAY',
  'SILO',
  'HOPPER',
  'TRANSFER_TOWER',
  'CONVEYOR',
  'LIQUID_TANK',
  'BUND_WALL',
  'PUMP_STATION',
  'PIPELINE',
  'PIPE_RACK',
  'LOADING_ARM',
  'BERTH',
  'FENDER',
  'BOLLARD',
  'RORO_RAMP',
  'GATE',
  'WEIGHBRIDGE',
  'JUNCTION',
  'TRUCK_HOLDING',
  'PARKING_YARD',
  'INSPECTION_BAY',
  'DISPATCH_LANE',
  'FIRE_ACCESS',
  'INTERNAL_ROAD',
  'EXCLUSION_ZONE',
  'DRAINAGE_ZONE',
  'FENCE',
  'TERMINAL_BOUNDARY',
  'SURFACE_AREA',
  'ROAD_SEGMENT',
  'CONTAINER_YARD',
]

const REQUIRED_KEYS = [
  'MOBILE_HARBOUR_CRANE',
  'LEVEL_LUFFING_CRANE',
  'CRAWLER_CRANE',
  'HARBOUR_GANTRY_CRANE',
  'SHIP_UNLOADER',
  'QUAY_CRANE_RAIL',
  'STS_CRANE',
  'RTG',
  'FORKLIFT',
  'REACH_STACKER',
  'WHEEL_LOADER',
  'TERMINAL_TRACTOR',
  'EXCAVATOR',
  'MOBILE_CONVEYOR_LOADER',
  'FLATBED_TRUCK',
  'LOW_BED_TRAILER',
  'DUMP_TRUCK',
  'TANK_TRUCK',
  'RORO_TRANSPORTER',
]

describe('model registry', () => {
  it('has a model for each of the 58 asset types, once', () => {
    const keys = MODEL_SPECS.map((s) => s.key)
    expect(keys).toHaveLength(58)
    expect([...keys].sort()).toEqual([...REQUIRED_KEYS, ...PART2_KEYS].sort())
    expect(getModelSpec('NO_SUCH_MODEL')).toBeUndefined()
  })

  it('finds a model by the lower-case model_key of the asset-type API as well as by the type code', () => {
    expect(getModelSpec('sts_crane')).toBe(getModelSpec('STS_CRANE'))
    expect(getModelSpec('container_yard')?.key).toBe('CONTAINER_YARD')
  })

  describe.each(MODEL_SPECS.filter((s) => !s.parametric).map((s) => [s.key, s] as const))(
    '%s',
    (_key, spec) => {
      const built = buildModel(spec)
      const box = new Box3().setFromObject(built.group)
      const size = box.getSize(new Vector3())

      it('uses at most 6 draw calls', () => {
        expect(built.drawCalls).toBeGreaterThan(0)
        expect(built.drawCalls).toBeLessThanOrEqual(6)
      })

      it('measures its declared size, centred on the footprint at ground', () => {
        expect(size.x).toBeCloseTo(spec.size.length, 3)
        expect(size.z).toBeCloseTo(spec.size.width, 3)
        expect(size.y).toBeCloseTo(tallest(spec), 3)
        expect(box.min.y).toBeCloseTo(0, 5)
        expect((box.min.x + box.max.x) / 2).toBeCloseTo(0, 5)
        expect((box.min.z + box.max.z) / 2).toBeCloseTo(0, 5)
      })

      it('declares pivots inside its own bounds', () => {
        expect(Object.keys(built.pivots).length).toBeGreaterThan(0)
        const grown = box.clone().expandByScalar(0.01)
        for (const point of Object.values(built.pivots)) {
          expect(grown.containsPoint(new Vector3(...point))).toBe(true)
        }
      })

      it('is matte: no shiny material', () => {
        for (const mesh of built.group.children) {
          const material = (
            mesh as unknown as { material: { roughness: number; metalness: number } }
          ).material
          expect(material.roughness).toBe(1)
          expect(material.metalness).toBe(0)
        }
      })
    },
  )

  it('keeps the shapes the ticket names', () => {
    const colours = (key: string) =>
      buildModel(getModelSpec(key)!).group.children.map((m) => m.name.replace('part-', ''))
    expect(colours('MOBILE_HARBOUR_CRANE')).toEqual(
      expect.arrayContaining(['6b7480', '2b3442', 'f2622e', 'ffffff', 'e9e5dc']),
    )
    expect(colours('STS_CRANE')).toEqual(expect.arrayContaining(['f2622e', 'e9e5dc', '3c6fb4']))
    expect(colours('RTG')).toContain('e8a317')
    expect(colours('HARBOUR_GANTRY_CRANE')).toContain('3c6fb4')
    expect(colours('QUAY_CRANE_RAIL')).toEqual(expect.arrayContaining(['f2622e', 'e8a317']))
  })

  it('puts the STS mast 16 m and the quay crane A-frame 14 m above the declared height', () => {
    expect(tallest(getModelSpec('STS_CRANE')!)).toBe(38 + 16)
    expect(tallest(getModelSpec('QUAY_CRANE_RAIL')!)).toBe(25 + 14)
  })

  it('spaces the STS rails 16 m apart', () => {
    const spec = getModelSpec('STS_CRANE')!
    expect(spec.size.width).toBeGreaterThan(16)
  })
})

describe('modelScale', () => {
  const spec = getModelSpec('MOBILE_HARBOUR_CRANE')!

  it('scales by size over default size on each axis', () => {
    expect(modelScale(spec, { length: 68, width: 8, height: 40 })).toEqual([2, 1, 0.5])
  })

  it('makes the drawn model the size of the resized asset', () => {
    const size = { length: 40, width: 20, height: 30 }
    const built = buildModel(spec)
    built.group.scale.set(...modelScale(spec, size))
    const drawn = new Box3().setFromObject(built.group).getSize(new Vector3())
    expect(drawn.toArray().map((n) => Math.round(n * 1000) / 1000)).toEqual([40, 30, 20])
    expect(modelEnvelopeSize(spec, size)).toEqual(size)
  })

  it('keeps the crane height ratio including the A-frame', () => {
    const quay = getModelSpec('QUAY_CRANE_RAIL')!
    const envelope = modelEnvelopeSize(quay, { ...quay.size, height: quay.size.height * 2 })
    expect(envelope.height).toBeCloseTo((25 + 14) * 2)
  })
})
