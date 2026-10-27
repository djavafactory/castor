import type { BoxBatch, Pt } from './box-batch'
import { COLOUR, type ModelParams, type ModelSpec } from './model-spec'
import { offsetPath, pathOf, ringOf } from './outline'
import { filledCount } from './storage'

const { STEEL, DARK, BOOM, ROOF, NEUTRAL, ROAD, WHITE, CAR_RED, BLUE, AMBER, GLASS } = COLOUR

/** Thickness of tarmac, metres. */
const TARMAC = 0.12
/** Painted markings sit on top of it. */
const PAINT = 0.03
/** Width of one parking bay, and its depth, metres. */
const BAY_WIDTH = 2.6
const BAY_DEPTH = 5

const gate: ModelSpec = {
  key: 'GATE',
  label: 'Gate',
  category: 'traffic',
  size: { length: 12, width: 10, height: 5 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const booth = 2.2
    b.block(ROAD, -l / 2, l / 2, 0, TARMAC, -w / 2, w / 2)
    for (const side of [-1, 1]) {
      const z0 = side < 0 ? -w / 2 : w / 2 - booth
      b.block(BOOM, -1.5, 1.5, TARMAC, 2.8, z0, z0 + booth) // booth
      b.block(
        GLASS,
        -1.4,
        1.4,
        1.2,
        2.4,
        side < 0 ? z0 + booth - 0.1 : z0,
        side < 0 ? z0 + booth : z0 + 0.1,
      )
    }
    b.block(ROOF, -l / 2, l / 2, h - 0.4, h, -w / 2, w / 2) // canopy
    for (const x of [-l / 2 + 0.3, l / 2 - 0.3]) {
      for (const z of [-w / 2 + 0.3, w / 2 - 0.3])
        b.block(STEEL, x - 0.2, x + 0.2, TARMAC, h - 0.4, z - 0.2, z + 0.2)
    }
    b.slab(CAR_RED, [1.8, -w / 2 + booth], [1.8, w / 2 - booth], 0.15, 1, 1.2) // barrier arm
    b.block(DARK, 1.6, 2, TARMAC, 1.2, w / 2 - booth - 0.4, w / 2 - booth)
    return {}
  },
}

const weighbridge: ModelSpec = {
  key: 'WEIGHBRIDGE',
  label: 'Weighbridge',
  category: 'traffic',
  size: { length: 18, width: 7, height: 2.6 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(STEEL, -l / 2, l / 2, 0, 0.35, -w / 2, -w / 2 + Math.min(3.4, w * 0.55)) // slab
    b.block(NEUTRAL, -1.5, 1.5, 0, h - 0.3, w / 2 - 2.3, w / 2) // hut
    b.block(ROOF, -1.7, 1.7, h - 0.3, h, w / 2 - 2.3, w / 2)
    return {}
  },
}

/** Tarmac of a drawn area. */
function tarmac(b: BoxBatch, p: ModelParams): Pt[] {
  const ring = ringOf(p)
  b.prism(ROAD, ring, 0, TARMAC)
  return ring
}

const junction: ModelSpec = {
  key: 'JUNCTION',
  label: 'Junction',
  category: 'traffic',
  size: { length: 14, width: 14, height: TARMAC + PAINT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w } = p.size
    tarmac(b, p)
    b.dashes(
      WHITE,
      [
        [-l / 2, 0],
        [l / 2, 0],
      ],
      0.15,
      TARMAC,
      TARMAC + PAINT,
      2,
      2,
    )
    b.dashes(
      WHITE,
      [
        [0, -w / 2],
        [0, w / 2],
      ],
      0.15,
      TARMAC,
      TARMAC + PAINT,
      2,
      2,
    )
    return {}
  },
}

const truckHolding: ModelSpec = {
  key: 'TRUCK_HOLDING',
  label: 'Truck holding',
  category: 'traffic',
  size: { length: 40, width: 25, height: TARMAC + PAINT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const ring = tarmac(b, p) // no parked trucks in Phase 1
    b.ribbon(WHITE, ring, 0.2, TARMAC, TARMAC + PAINT, true)
    return {}
  },
}

/** Rows of bays across the depth of a yard. */
function bayRows(width: number): number {
  return Math.max(1, Math.floor(width / (BAY_DEPTH + 0.5)))
}

const parkingYard: ModelSpec = {
  key: 'PARKING_YARD',
  label: 'Parking yard',
  category: 'traffic',
  size: { length: 40, width: 20, height: 1.6 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.5 },
  author(b, p) {
    const { length: l, width: w } = p.size
    b.block(ROAD, -l / 2, l / 2, 0, TARMAC, -w / 2, w / 2)
    const bays = Math.max(1, Math.floor(l / BAY_WIDTH))
    const rows = bayRows(w)
    const cell = w / rows
    const slots: Pt[] = []
    for (let r = 0; r < rows; r++) {
      const zc = -w / 2 + cell * (r + 0.5)
      for (let i = 0; i <= bays; i++) {
        const x = -l / 2 + i * BAY_WIDTH // bay lines every 2.6 m
        b.block(
          WHITE,
          x - 0.05,
          x + 0.05,
          TARMAC,
          TARMAC + PAINT,
          zc - BAY_DEPTH / 2,
          zc + BAY_DEPTH / 2,
        )
      }
      for (let i = 0; i < bays; i++) slots.push([-l / 2 + BAY_WIDTH * (i + 0.5), zc])
    }
    const colours = [WHITE, CAR_RED, STEEL, BLUE]
    slots.slice(0, filledCount(slots.length, p.details.designFill, 0.5)).forEach(([x, z], n) => {
      b.block(
        colours[n % colours.length]!,
        x - 0.85,
        x + 0.85,
        TARMAC + 0.25,
        TARMAC + 0.85,
        z - 2.1,
        z + 2.1,
      )
      b.block(GLASS, x - 0.75, x + 0.75, TARMAC + 0.85, TARMAC + 1.35, z - 1.1, z + 0.9)
    })
    return {}
  },
}

const inspectionBay: ModelSpec = {
  key: 'INSPECTION_BAY',
  label: 'Inspection bay',
  category: 'traffic',
  size: { length: 14, width: 8, height: 5 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(ROAD, -l / 2, l / 2, 0, TARMAC, -w / 2, w / 2)
    for (const x of [-l / 2 + 0.3, l / 2 - 0.3]) {
      for (const z of [-w / 2 + 0.3, w / 2 - 0.3])
        b.block(STEEL, x - 0.2, x + 0.2, TARMAC, h - 0.4, z - 0.2, z + 0.2)
    }
    b.block(AMBER, -l / 2, l / 2, h - 0.4, h, -w / 2, w / 2) // roof
    return {}
  },
}

/** A road ribbon along a path: tarmac, painted edge lines and, when asked for, a dashed centre line. */
function road(b: BoxBatch, p: ModelParams, edge: number, centre: boolean) {
  const path = pathOf(p)
  const w = p.size.width
  b.ribbon(ROAD, path, w, 0, TARMAC)
  for (const side of [1, -1]) {
    b.ribbon(edge, offsetPath(path, side * (w / 2 - 0.25)), 0.2, TARMAC, TARMAC + PAINT)
  }
  if (centre) b.dashes(WHITE, path, 0.15, TARMAC, TARMAC + PAINT, 3, 3)
}

const dispatchLane: ModelSpec = {
  key: 'DISPATCH_LANE',
  label: 'Dispatch lane',
  category: 'traffic',
  size: { length: 60, width: 4, height: TARMAC + PAINT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-30, 0],
    [30, 0],
  ],
  author(b, p) {
    road(b, p, AMBER, false)
    return {}
  },
}

const fireAccess: ModelSpec = {
  key: 'FIRE_ACCESS',
  label: 'Fire access',
  category: 'traffic',
  size: { length: 60, width: 4, height: TARMAC + PAINT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-30, 0],
    [30, 0],
  ],
  author(b, p) {
    road(b, p, CAR_RED, false)
    return {}
  },
}

const internalRoad: ModelSpec = {
  key: 'INTERNAL_ROAD',
  label: 'Internal road',
  category: 'traffic',
  size: { length: 100, width: 7, height: TARMAC + PAINT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-50, 0],
    [50, 0],
  ],
  author(b, p) {
    road(b, p, WHITE, true)
    return {}
  },
}

export const TRAFFIC_MODELS: ModelSpec[] = [
  gate,
  weighbridge,
  junction,
  truckHolding,
  parkingYard,
  inspectionBay,
  dispatchLane,
  fireAccess,
  internalRoad,
]
