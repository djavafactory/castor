import { COLOUR, type ModelSpec } from './model-spec'
import { pathOf, pointAlong, stations } from './outline'

const { AMBER, DARK, RAMP } = COLOUR

/** The amber strip along the quay edge, metres wide. */
const EDGE_STRIP = 1.2
/** Distance of the water-side crane rail from the quay edge, and the rail gauge of the quay cranes, metres. */
const RAIL_SETBACK = 2.5
const RAIL_GAUGE = 16

const berth: ModelSpec = {
  key: 'BERTH',
  label: 'Berth',
  category: 'marine',
  size: { length: 200, width: 30, height: 0.2 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  details: { name: 'Berth', depth: 14, railCranes: true },
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.block(AMBER, -l / 2, l / 2, 0, h, -w / 2, -w / 2 + EDGE_STRIP) // quay edge, water on -Z
    if (p.details.railCranes) {
      const water = -w / 2 + RAIL_SETBACK
      for (const z of [water, water + RAIL_GAUGE]) {
        if (z < w / 2 - 0.25) b.block(DARK, -l / 2, l / 2, 0, h, z - 0.25, z + 0.25) // crane rails
      }
    }
    return {}
  },
  caption: (p) => ({
    text: `${p.details.name ?? 'Berth'}  ${Math.round(p.size.length)} m / ${p.details.depth ?? 0} m deep`,
    height: 6,
  }),
}

const fender: ModelSpec = {
  key: 'FENDER',
  label: 'Fender',
  category: 'marine',
  size: { length: 60, width: 1.2, height: 1.5 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-30, 0],
    [30, 0],
  ],
  author(b, p) {
    const path = pathOf(p)
    for (const d of stations(path, 6, 3)) {
      const { at, dir } = pointAlong(path, d)
      const half = 0.5 // blocks are 1 m long along the quay
      b.slab(
        DARK,
        [at[0] - dir[0] * half, at[1] - dir[1] * half],
        [at[0] + dir[0] * half, at[1] + dir[1] * half],
        p.size.width,
        0,
        p.size.height,
      )
    }
    return {}
  },
}

const bollard: ModelSpec = {
  key: 'BOLLARD',
  label: 'Bollard',
  category: 'marine',
  size: { length: 1.2, width: 1.2, height: 1 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const h = p.size.height
    const r = Math.min(p.size.length, p.size.width) / 2
    b.frustum(DARK, [0, h * 0.15, 0], r, r * 0.9, h * 0.3, 12) // base
    b.frustum(DARK, [0, h * 0.55, 0], r * 0.55, r * 0.55, h * 0.5, 12) // neck
    b.frustum(DARK, [0, h * 0.9, 0], r * 0.8, r * 0.8, h * 0.2, 12) // head
    return {}
  },
}

const roroRamp: ModelSpec = {
  key: 'RORO_RAMP',
  label: 'RoRo ramp',
  category: 'marine',
  size: { length: 14, width: 8, height: 3 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const { length: l, width: w, height: h } = p.size
    b.wedge(RAMP, -l / 2, l / 2, 0, h, -w / 2, w / 2)
    for (const z of [-w / 2, w / 2 - 0.3]) b.wedge(DARK, -l / 2, l / 2, 0, h, z, z + 0.3) // kerbs
    return {}
  },
}

export const MARINE_MODELS: ModelSpec[] = [berth, fender, bollard, roroRamp]
