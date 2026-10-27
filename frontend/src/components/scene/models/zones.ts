import { tint } from './box-batch'
import { COLOUR, type ModelSpec } from './model-spec'
import { pathOf, ringOf, stations, pointAlong, pathLength } from './outline'

const { ZONE_RED, ZONE_BLUE, STEEL, DARK, RING, PAVING } = COLOUR

const exclusionZone: ModelSpec = {
  key: 'EXCLUSION_ZONE',
  label: 'Exclusion zone',
  category: 'zone',
  size: { length: 40, width: 30, height: 1.8 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const ring = ringOf(p)
    b.prism(tint(ZONE_RED, 20), ring, 0, 0.05) // translucent fill
    b.ribbon(ZONE_RED, ring, 0.4, 0, 0.1, true) // outline
    for (const [x, z] of ring) b.block(ZONE_RED, x - 0.15, x + 0.15, 0, 1.8, z - 0.15, z + 0.15) // corner posts
    return {}
  },
}

const drainageZone: ModelSpec = {
  key: 'DRAINAGE_ZONE',
  label: 'Drainage zone',
  category: 'zone',
  size: { length: 40, width: 20, height: 0.1 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    const ring = ringOf(p)
    b.prism(tint(ZONE_BLUE, 35), ring, 0, 0.05)
    b.dashes(ZONE_BLUE, ring, 0.4, 0, 0.1, 2, 1.5, true) // dashed outline
    return {}
  },
}

const fence: ModelSpec = {
  key: 'FENCE',
  label: 'Fence',
  category: 'zone',
  size: { length: 80, width: 0.2, height: 2.4 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  path: [
    [-40, 0],
    [40, 0],
  ],
  author(b, p) {
    const path = pathOf(p)
    const h = p.size.height
    const total = pathLength(path)
    for (const d of stations(path, 4)) {
      const [x, z] = pointAlong(path, Math.min(d, total)).at
      b.block(STEEL, x - 0.1, x + 0.1, 0, h, z - 0.1, z + 0.1) // posts every 4 m
    }
    for (const level of [0.45, 0.85]) {
      b.ribbon(DARK, path, 0.08, h * level - 0.04, h * level + 0.04) // rails
    }
    return {}
  },
}

const terminalBoundary: ModelSpec = {
  key: 'TERMINAL_BOUNDARY',
  label: 'Terminal boundary',
  category: 'zone',
  size: { length: 200, width: 120, height: 0.2 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    b.ribbon(RING, ringOf(p), 0.8, 0, p.size.height, true) // the outline only, nothing inside
    return {}
  },
}

const surfaceArea: ModelSpec = {
  key: 'SURFACE_AREA',
  label: 'Surface area',
  category: 'zone',
  size: { length: 40, width: 30, height: 0.05 },
  extraHeight: 0,
  mobile: false,
  parametric: true,
  author(b, p) {
    b.prism(PAVING, ringOf(p), 0, p.size.height)
    return {}
  },
}

export const ZONE_MODELS: ModelSpec[] = [
  exclusionZone,
  drainageZone,
  fence,
  terminalBoundary,
  surfaceArea,
]
