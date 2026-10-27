import { COLOUR, type ModelSpec } from './model-spec'
import { pathOf, ringOf, stations, pointAlong, pathLength } from './outline'

const { STEEL, DARK, TANK, BLUE, PIPE, AMBER, PAD, NEUTRAL, ROOF, CONCRETE } = COLOUR

const liquidTank: ModelSpec = {
  key: 'LIQUID_TANK',
  label: 'Liquid tank',
  category: 'liquid',
  size: { length: 20, width: 20, height: 14 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { designFill: 0.6 },
  author(b, p) {
    const diameter = Math.min(p.size.length, p.size.width)
    const rise = diameter * 0.08
    const shell = p.size.height - rise
    const radius = diameter / 2 - 0.3 // the gauge stands in the footprint
    b.frustum(TANK, [0, shell / 2, 0], radius, radius, shell, 20)
    b.frustum(TANK, [0, shell + rise / 2, 0], radius, 0.3, rise, 20) // cone roof
    const level = Math.min(1, Math.max(0, p.details.designFill ?? 0.6)) * shell
    b.block(BLUE, radius, radius + 0.25, 0, Math.max(0.2, level), -0.2, 0.2) // gauge
    return {}
  },
}

const bundWall: ModelSpec = {
  key: 'BUND_WALL',
  label: 'Bund wall',
  category: 'liquid',
  size: { length: 44, width: 34, height: 2 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const ring = ringOf(p)
    b.prism(CONCRETE, ring, 0, 0.1) // floor
    b.ribbon(CONCRETE, ring, 0.4, 0, p.size.height, true) // walls along the drawn polygon
    return {}
  },
}

const pumpStation: ModelSpec = {
  key: 'PUMP_STATION',
  label: 'Pump station',
  category: 'liquid',
  size: { length: 9, width: 6, height: 4 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(PAD, -l / 2, l / 2, 0, 0.2, -w / 2, w / 2)
    b.block(NEUTRAL, -l / 2, -l / 2 + 4, 0.2, h - 0.3, -w / 2, w / 2) // pump house
    b.block(ROOF, -l / 2, -l / 2 + 4, h - 0.3, h, -w / 2, w / 2)
    const top = Math.min(2.6, h - 0.25) // the pipes stay inside the declared height
    for (const z of [-w / 4, w / 4]) {
      b.cylinder(BLUE, 'x', [l / 2 - 2.2, 0.9, z], 0.6, 2.2, 10) // pump motors
      b.tube(PIPE, [l / 2 - 1, 0.9, z], [l / 2 - 1, top, z], 0.25) // risers
    }
    b.tube(PIPE, [l / 2 - 1, top, -w / 4], [l / 2 - 1, top, w / 4], 0.25)
    return {}
  },
}

const pipeline: ModelSpec = {
  key: 'PIPELINE',
  label: 'Pipeline',
  category: 'liquid',
  size: { length: 64, width: 0.8, height: 3 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-32, 0],
    [32, 0],
  ],
  author(b, p) {
    const path = pathOf(p)
    const radius = p.size.width / 2
    const y = p.size.height - radius
    for (let i = 1; i < path.length; i++) {
      b.tube(PIPE, [path[i - 1]![0], y, path[i - 1]![1]], [path[i]![0], y, path[i]![1]], radius)
    }
    for (const d of stations(path, 8)) {
      const [x, z] = pointAlong(path, Math.min(d, pathLength(path))).at
      b.block(STEEL, x - 0.2, x + 0.2, 0, y - radius, z - 0.2, z + 0.2) // supports every 8 m
    }
    return {}
  },
}

const pipeRack: ModelSpec = {
  key: 'PIPE_RACK',
  label: 'Pipe rack',
  category: 'liquid',
  size: { length: 64, width: 3, height: 5 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-32, 0],
    [32, 0],
  ],
  author(b, p) {
    const path = pathOf(p)
    const { width: w, height: h } = p.size
    const colours = [PIPE, BLUE, AMBER]
    colours.forEach((colour, i) => {
      const z = (i - 1) * (w / 3)
      for (let k = 1; k < path.length; k++) {
        b.tube(
          colour,
          [path[k - 1]![0], h - 0.3, path[k - 1]![1] + z],
          [path[k]![0], h - 0.3, path[k]![1] + z],
          0.3,
        )
      }
    })
    for (const d of stations(path, 8)) {
      const { at, dir } = pointAlong(path, Math.min(d, pathLength(path)))
      const side: [number, number] = [-dir[1] * (w / 2 - 0.15), dir[0] * (w / 2 - 0.15)]
      for (const s of [1, -1]) {
        const x = at[0] + side[0] * s
        const z = at[1] + side[1] * s
        b.block(STEEL, x - 0.15, x + 0.15, 0, h - 0.6, z - 0.15, z + 0.15) // columns every 8 m
      }
      b.slab(
        DARK,
        [at[0] + side[0], at[1] + side[1]],
        [at[0] - side[0], at[1] - side[1]],
        0.3,
        h - 0.9,
        h - 0.6,
      )
    }
    return {}
  },
}

const loadingArm: ModelSpec = {
  key: 'LOADING_ARM',
  label: 'Loading arm',
  category: 'liquid',
  size: { length: 6, width: 3, height: 7 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    const x = -l / 2 + 1
    b.block(DARK, -l / 2, l / 2, 0, 0.3, -w / 2, w / 2) // base
    b.cylinder(STEEL, 'y', [x, (h - 0.4) / 2, 0], 0.3, h - 0.4, 8) // riser
    b.beam(STEEL, [x, h - 0.4, 0], [l / 2 - 0.5, h - 1.2, 0], 0.4) // inboard arm
    b.beam(STEEL, [l / 2 - 0.5, h - 1.2, 0], [l / 2 - 0.5, 3.4, 0], 0.3) // outboard arm
    b.block(AMBER, l / 2 - 0.9, l / 2 - 0.1, 2.6, 3.4, -0.4, 0.4) // coupling
    return {}
  },
}

export const LIQUID_MODELS: ModelSpec[] = [
  liquidTank,
  bundWall,
  pumpStation,
  pipeline,
  pipeRack,
  loadingArm,
]
