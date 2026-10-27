import type { BoxBatch, Pt } from './box-batch'
import { COLOUR, type CargoForm, type ModelParams, type ModelSpec } from './model-spec'
import { edgeDistance, insideRing, ringOf } from './outline'

const { PAD, WHITE, DARK, PILE, STACK, SILO, COIL, CONCRETE } = COLOUR

/** Thickness of a pad, metres. */
const PAD_TOP = 0.2
/** Coil grid of laydown yards and quay staging, metres. */
const COIL_STEP_X = 3.4
const COIL_STEP_Z = 3.2
const COIL_RADIUS = 1.4
const COIL_HEIGHT = 1.6

/** Design fill as a number of the `total` places: 0..1 of them, rounded. */
export function filledCount(total: number, fill: number | undefined, fallback: number): number {
  return Math.round(total * Math.min(1, Math.max(0, fill ?? fallback)))
}

/** One slot of cargo, drawn static at the pad's maximum height. */
function cargo(
  b: BoxBatch,
  form: CargoForm,
  cx: number,
  cz: number,
  slotL: number,
  slotW: number,
  maxHeight: number,
) {
  if (form === 'PILE') {
    b.taper(PILE, [cx, PAD_TOP, cz], slotL - 2, slotW - 2, maxHeight - PAD_TOP, 0.55, 0.15)
  } else if (form === 'STACK') {
    b.taper(STACK, [cx, PAD_TOP, cz], slotL - 1.5, slotW - 1.5, maxHeight - PAD_TOP, 0.92, 0.92)
  } else {
    const diameter = Math.min(slotL, slotW) - 1
    const height = 2 * maxHeight
    b.frustum(SILO, [cx, PAD_TOP + height / 2, cz], diameter / 2, diameter / 2, height, 14)
  }
}

const storagePad: ModelSpec = {
  key: 'STORAGE_PAD',
  label: 'Storage pad',
  category: 'storage',
  size: { length: 60, width: 40, height: 8 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { bays: 4, rows: 2, designFill: 0.6, cargoForm: 'PILE', name: 'Storage pad' },
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const bays = Math.max(1, p.details.bays ?? 4)
    const rows = Math.max(1, p.details.rows ?? 2)
    b.block(PAD, -l / 2, l / 2, 0, PAD_TOP, -w / 2, w / 2)
    // The painted slot grid
    for (let i = 0; i <= bays; i++) {
      const x = -l / 2 + (l * i) / bays
      b.block(
        WHITE,
        Math.max(-l / 2, x - 0.1),
        Math.min(l / 2, x + 0.1),
        PAD_TOP,
        PAD_TOP + 0.03,
        -w / 2,
        w / 2,
      )
    }
    for (let j = 0; j <= rows; j++) {
      const z = -w / 2 + (w * j) / rows
      b.block(
        WHITE,
        -l / 2,
        l / 2,
        PAD_TOP,
        PAD_TOP + 0.03,
        Math.max(-w / 2, z - 0.1),
        Math.min(w / 2, z + 0.1),
      )
    }
    const slotL = l / bays
    const slotW = w / rows
    const count = filledCount(bays * rows, p.details.designFill, 0.6)
    for (let n = 0; n < count; n++) {
      const x = -l / 2 + slotL * ((n % bays) + 0.5)
      const z = -w / 2 + slotW * (Math.floor(n / bays) + 0.5)
      cargo(b, p.details.cargoForm ?? 'PILE', x, z, slotL, slotW, h)
    }
    return {}
  },
  caption: (p) => ({ text: p.details.name ?? 'Storage pad', height: p.size.height + 3 }),
}

/** A pad with its outline and coils on the 3.4 / 3.2 m grid, the first `designFill` of the places filled. */
function coilYard(b: BoxBatch, p: ModelParams) {
  const ring = ringOf(p)
  b.prism(PAD, ring, 0, PAD_TOP)
  b.ribbon(DARK, ring, 0.3, PAD_TOP, PAD_TOP + 0.05, true)
  const xs = ring.map((q) => q[0])
  const zs = ring.map((q) => q[1])
  const places: Pt[] = []
  for (let z = Math.min(...zs) + COIL_STEP_Z / 2; z < Math.max(...zs); z += COIL_STEP_Z) {
    for (let x = Math.min(...xs) + COIL_STEP_X / 2; x < Math.max(...xs); x += COIL_STEP_X) {
      if (insideRing(ring, [x, z]) && edgeDistance(ring, [x, z]) >= COIL_RADIUS) places.push([x, z])
    }
  }
  for (const [x, z] of places.slice(0, filledCount(places.length, p.details.designFill, 0.6))) {
    b.cylinder(COIL, 'y', [x, PAD_TOP + COIL_HEIGHT / 2, z], COIL_RADIUS, COIL_HEIGHT, 10)
  }
}

const laydownYard: ModelSpec = {
  key: 'LAYDOWN_YARD',
  label: 'Laydown yard',
  category: 'storage',
  size: { length: 50, width: 30, height: PAD_TOP + COIL_HEIGHT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.6 },
  author(b, p) {
    coilYard(b, p)
    return {}
  },
}

const quayStaging: ModelSpec = {
  key: 'QUAY_STAGING',
  label: 'Quay staging',
  category: 'storage',
  size: { length: 60, width: 16, height: PAD_TOP + COIL_HEIGHT },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.6 },
  author(b, p) {
    coilYard(b, p)
    return {}
  },
}

const stockpileBay: ModelSpec = {
  key: 'STOCKPILE_BAY',
  label: 'Stockpile bay',
  category: 'storage',
  size: { length: 30, width: 20, height: 6 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.8 },
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const wall = 0.5
    const wallHeight = 3.6
    b.block(PAD, -l / 2, l / 2, 0, 0.1, -w / 2, w / 2)
    b.block(CONCRETE, -l / 2, -l / 2 + wall, 0, wallHeight, -w / 2, w / 2) // back
    b.block(CONCRETE, -l / 2, l / 2, 0, wallHeight, -w / 2, -w / 2 + wall) // sides, open on +X
    b.block(CONCRETE, -l / 2, l / 2, 0, wallHeight, w / 2 - wall, w / 2)
    const inner = { l: l - wall, w: w - 2 * wall }
    const height = Math.max(0.5, (p.details.designFill ?? 0.8) * (h - 0.1))
    b.taper(
      PILE,
      [-l / 2 + wall + inner.l * 0.45, 0.1, 0],
      inner.l * 0.85,
      inner.w * 0.85,
      height,
      0.5,
      0.4,
    )
    return {}
  },
}

export const STORAGE_MODELS: ModelSpec[] = [storagePad, laydownYard, quayStaging, stockpileBay]
