import { COLOUR, type ModelSpec } from './model-spec'
import { offsetPath, pathLength, pathOf, pointAlong, stations } from './outline'

const { STEEL, DARK, SILO, GAUGE, HOPPER, BOOM, CONCRETE } = COLOUR

const silo: ModelSpec = {
  key: 'SILO',
  label: 'Silo',
  category: 'bulk',
  size: { length: 14, width: 14, height: 30 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.6 },
  author(b, p) {
    const diameter = Math.min(p.size.length, p.size.width)
    const rise = diameter * 0.18
    const body = p.size.height - rise
    const radius = diameter / 2 - 0.3 // the ladder and gauge stand in the footprint
    b.frustum(SILO, [0, body / 2, 0], radius, radius, body, 16)
    b.dome(SILO, [0, body, 0], radius, rise, 16)
    b.block(DARK, -radius - 0.3, -radius, 0, body, -0.25, 0.25) // ladder
    const level = Math.min(1, Math.max(0, p.details.designFill ?? 0.6)) * body
    b.block(GAUGE, radius, radius + 0.25, 0, Math.max(0.2, level), -0.2, 0.2) // fill gauge
    return {}
  },
}

const hopper: ModelSpec = {
  key: 'HOPPER',
  label: 'Hopper',
  category: 'bulk',
  size: { length: 6, width: 6, height: 8 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const legs = h / 2
    for (const x of [-l / 2 + 0.3, l / 2 - 0.3]) {
      for (const z of [-w / 2 + 0.3, w / 2 - 0.3])
        b.block(STEEL, x - 0.25, x + 0.25, 0, legs, z - 0.25, z + 0.25)
    }
    // Inverted funnel: wide at the top, narrow outlet at the bottom
    b.frustum(HOPPER, [0, legs + (h - legs) / 2, 0], 0.6, Math.min(l, w) / 2, h - legs, 8)
    return {}
  },
}

const transferTower: ModelSpec = {
  key: 'TRANSFER_TOWER',
  label: 'Transfer tower',
  category: 'bulk',
  size: { length: 8, width: 8, height: 25 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const house = Math.min(6, h / 4)
    for (const x of [-l / 2 + 0.4, l / 2 - 0.4]) {
      for (const z of [-w / 2 + 0.4, w / 2 - 0.4])
        b.block(STEEL, x - 0.4, x + 0.4, 0, h - house, z - 0.4, z + 0.4)
    }
    for (let y = 6; y < h - house; y += 6) b.block(DARK, -l / 2, l / 2, y, y + 0.3, -w / 2, w / 2) // platforms
    for (let y = 0; y + 6 <= h - house; y += 6) {
      b.beam(STEEL, [-l / 2 + 0.4, y + 0.3, w / 2 - 0.4], [l / 2 - 0.4, y + 5.7, w / 2 - 0.4], 0.25) // bracing
    }
    b.block(BOOM, -l / 2, l / 2, h - house, h - 0.4, -w / 2, w / 2) // drive house
    b.block(CONCRETE, -l / 2, l / 2, h - 0.4, h, -w / 2, w / 2)
    return {}
  },
}

const conveyor: ModelSpec = {
  key: 'CONVEYOR',
  label: 'Conveyor',
  category: 'bulk',
  size: { length: 80, width: 2.4, height: 6 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-40, 0],
    [40, 0],
  ],
  author(b, p) {
    const path = pathOf(p)
    const { width: w, height: h } = p.size
    b.ribbon(DARK, path, w * 0.7, h - 0.7, h - 0.4) // belt at height
    for (const side of [1, -1]) {
      b.ribbon(STEEL, offsetPath(path, (side * (w - 0.2)) / 2), 0.2, h - 0.7, h) // side rails
    }
    for (const d of stations(path, 10)) {
      const { at, dir } = pointAlong(path, Math.min(d, pathLength(path)))
      const side: [number, number] = [-dir[1] * (w / 2 - 0.15), dir[0] * (w / 2 - 0.15)]
      for (const s of [1, -1]) {
        const x = at[0] + side[0] * s
        const z = at[1] + side[1] * s
        b.block(STEEL, x - 0.15, x + 0.15, 0, h - 0.7, z - 0.15, z + 0.15) // trestle legs
      }
      b.slab(
        STEEL,
        [at[0] + side[0], at[1] + side[1]],
        [at[0] - side[0], at[1] - side[1]],
        0.25,
        h - 0.95,
        h - 0.7,
      ) // cross member
    }
    return {}
  },
}

export const BULK_MODELS: ModelSpec[] = [silo, hopper, transferTower, conveyor]
