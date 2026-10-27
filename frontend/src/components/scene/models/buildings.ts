import type { BoxBatch } from './box-batch'
import { COLOUR, type ModelParams, type ModelSpec } from './model-spec'
import { ringOf } from './outline'

const { NEUTRAL, ROOF, DARK, STEEL, PAD } = COLOUR

/** Depth of the canopy over the dock doors, metres. */
const CANOPY = 3

/**
 * A box shed with a gabled roof and doors along its +Z face: `doors` doors, or one every `every` metres.
 * Returns where the body ends on +Z. The canopy over the doors, when asked for, fills the last CANOPY metres.
 */
function shed(
  b: BoxBatch,
  p: ModelParams,
  rise: number,
  doors: { count?: number; every?: number },
  canopy: boolean,
) {
  const { length: l, width: w, height: h } = p.size
  const eave = h - rise
  const edge = canopy ? w / 2 - CANOPY : w / 2
  b.block(NEUTRAL, -l / 2, l / 2, 0, eave, -w / 2, edge)
  b.gable(ROOF, -l / 2, l / 2, -w / 2, edge, eave, rise)
  const count = doors.count ?? Math.max(1, Math.floor((l - 6) / (doors.every ?? 12)) + 1)
  const pitch = doors.every ?? (l - 8) / Math.max(1, count - 1)
  const first = doors.every ? -((count - 1) * pitch) / 2 : -(l - 8) / 2
  for (let i = 0; i < count; i++) {
    const x = count === 1 ? 0 : first + i * pitch
    b.block(DARK, x - 2, x + 2, 0, Math.min(4.5, eave - 0.5), edge - 0.1, edge + 0.05)
  }
  if (canopy) {
    b.block(ROOF, -l / 2, l / 2, eave - 1, eave - 0.7, edge, w / 2)
    for (const x of [-l / 2 + 0.2, l / 2 - 0.2])
      b.block(STEEL, x - 0.15, x + 0.15, 0, eave - 1, w / 2 - 0.3, w / 2)
  }
}

const warehousePoly: ModelSpec = {
  key: 'WAREHOUSE_POLY',
  label: 'Warehouse (drawn)',
  category: 'building',
  size: { length: 60, width: 40, height: 10 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  footprint: [
    [-30, -20],
    [30, -20],
    [30, 0],
    [0, 0],
    [0, 20],
    [-30, 20],
  ],
  author(b, p) {
    const ring = ringOf(p)
    const h = p.size.height
    b.prism(ROOF, ring, 0, 0.1) // floor
    b.prism(NEUTRAL, ring, 0.1, h - 0.4) // walls: the drawn footprint to height
    b.prism(ROOF, ring, h - 0.4, h) // roof
    return {}
  },
}

const warehouse: ModelSpec = {
  key: 'WAREHOUSE',
  label: 'Warehouse',
  category: 'building',
  size: { length: 60, width: 25, height: 11 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { doors: 6, roofRise: 3 },
  author(b, p) {
    shed(b, p, p.details.roofRise ?? 3, { count: p.details.doors ?? 6 }, true)
    return {}
  },
}

const transitShed: ModelSpec = {
  key: 'TRANSIT_SHED',
  label: 'Transit shed',
  category: 'building',
  size: { length: 84, width: 20, height: 9 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { roofRise: 2.5 },
  author(b, p) {
    shed(b, p, p.details.roofRise ?? 2.5, { every: 12 }, false)
    return {}
  },
}

const coveredBay: ModelSpec = {
  key: 'COVERED_BAY',
  label: 'Covered bay',
  category: 'building',
  size: { length: 40, width: 20, height: 8 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(PAD, -l / 2, l / 2, 0, 0.1, -w / 2, w / 2)
    const columns = Math.max(1, Math.round(l / 10))
    for (let i = 0; i <= columns; i++) {
      const x = -l / 2 + 0.2 + ((l - 0.4) * i) / columns
      for (const z of [-w / 2 + 0.2, w / 2 - 0.2])
        b.block(STEEL, x - 0.2, x + 0.2, 0.1, h - 0.5, z - 0.2, z + 0.2)
    }
    b.block(ROOF, -l / 2, l / 2, h - 0.5, h, -w / 2, w / 2) // flat roof
    return {}
  },
}

const building: ModelSpec = {
  key: 'BUILDING',
  label: 'Building',
  category: 'building',
  size: { length: 20, width: 12, height: 8 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(NEUTRAL, -l / 2, l / 2, 0, h - 0.4, -w / 2, w / 2)
    b.block(ROOF, -l / 2, l / 2, h - 0.4, h, -w / 2, w / 2) // roof slab
    return {}
  },
}

export const BUILDING_MODELS: ModelSpec[] = [
  warehousePoly,
  warehouse,
  transitShed,
  coveredBay,
  building,
]
