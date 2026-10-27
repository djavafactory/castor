import type { BoxBatch } from './box-batch'
import { COLOUR, type ModelSpec } from './model-spec'

const { STEEL, DARK, ORANGE, WHITE, BOOM, BLUE, AMBER } = COLOUR

/** Four corner legs between the given x and z positions. */
function cornerLegs(
  b: BoxBatch,
  colour: number,
  xs: [number, number],
  zs: [number, number],
  y0: number,
  y1: number,
  t: number,
) {
  for (const x of xs) {
    for (const z of zs) b.block(colour, x - t / 2, x + t / 2, y0, y1, z - t / 2, z + t / 2)
  }
}

const mobileHarbourCrane: ModelSpec = {
  key: 'MOBILE_HARBOUR_CRANE',
  label: 'Mobile harbour crane',
  category: 'crane',
  size: { length: 34, width: 16, height: 40 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(STEEL, -17, 5, 1.2, 3.6, -3.5, 3.5) // chassis
    b.block(DARK, -14, -11, 0, 1.2, -8, 8) // outriggers
    b.block(DARK, -2, 1, 0, 1.2, -8, 8)
    b.block(ORANGE, -9.5, -4.5, 3.6, 8, -2.5, 2.5) // tapered tower
    b.block(ORANGE, -9, -5, 8, 12, -2, 2)
    b.block(ORANGE, -8.5, -5.5, 12, 16, -1.5, 1.5)
    b.block(WHITE, -5.6, -2.4, 13.5, 16, -2.8, -0.8) // cab
    b.beam(BOOM, [-7, 16, 0], [17, 40, 0], 1.6) // 45 degrees
    b.block(DARK, 16.4, 16.8, 28, 40, -0.2, 0.2) // hoist rope
    b.block(DARK, 16, 17.2, 27, 28.4, -0.6, 0.6) // hook block
    return { boom: [-7, 16, 0], hook: [16.8, 40, 0] }
  },
}

const levelLuffingCrane: ModelSpec = {
  key: 'LEVEL_LUFFING_CRANE',
  label: 'Level luffing crane',
  category: 'crane',
  size: { length: 24, width: 16, height: 50 },
  extraHeight: 0,
  mobile: false,
  author(b) {
    cornerLegs(b, STEEL, [-7.25, 1.25], [-7.25, 7.25], 0, 12, 1.5) // portal base
    b.block(STEEL, -8, 2, 12, 13.5, -8, 8)
    b.block(STEEL, -8, 2, 12, 13.5, -1, 1)
    b.block(ORANGE, -5, -1, 13.5, 24, -2, 2) // tower
    b.block(ORANGE, -4.4, -1.6, 24, 32, -1.4, 1.4)
    b.block(WHITE, -7, -1, 32, 36, -2.5, 2.5) // house
    b.block(DARK, -12, -7, 30, 35, -2.2, 2.2) // counterweight
    b.lattice(BOOM, [-2, 34, 0], [12, 50, 0], [0, 0, 1], 2.2)
    return { boom: [-2, 34, 0], house: [-4, 32, 0] }
  },
}

const crawlerCrane: ModelSpec = {
  key: 'CRAWLER_CRANE',
  label: 'Crawler crane',
  category: 'crane',
  size: { length: 12, width: 8, height: 40 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(DARK, -5, 5, 0, 1.8, -4, -1.6) // two tracks
    b.block(DARK, -5, 5, 0, 1.8, 1.6, 4)
    b.block(STEEL, -4, 4, 1.8, 3.6, -3, 3) // body
    b.block(ORANGE, -3.8, 1, 3.6, 5.2, -2.4, 2.4) // machinery house
    b.block(WHITE, 0.2, 2.6, 3.6, 5.6, -3, -1.4) // cab
    b.block(DARK, -6, -3.6, 2.4, 4.6, -2.2, 2.2) // counterweight
    b.lattice(BOOM, [2, 4, 0], [6, 40, 0], [0, 0, 1], 1.6, 0.3)
    return { boom: [2, 4, 0] }
  },
}

const harbourGantryCrane: ModelSpec = {
  key: 'HARBOUR_GANTRY_CRANE',
  label: 'Harbour gantry crane',
  category: 'crane',
  size: { length: 22, width: 60, height: 40 },
  extraHeight: 0,
  mobile: false,
  author(b) {
    cornerLegs(b, BLUE, [-9, 9], [0, 12], 1, 18, 1.4) // portal on two rails 12 m apart
    b.block(BLUE, -10, 10, 18, 19.5, -1, 13)
    for (const x of [-9, 9]) b.block(DARK, x - 1, x + 1, 0, 1, -1, 13) // bogies
    b.beam(BLUE, [-8, 19.5, 6], [0, 40, 3], 1) // mast
    b.beam(BLUE, [8, 19.5, 6], [0, 40, 3], 1)
    b.lattice(BLUE, [0, 24, 24], [0, 30, -36], [1, 0, 0], 3) // boom toward the water
    b.block(STEEL, -3, 3, 19.5, 23, 2, 14) // machinery house
    b.block(WHITE, 3, 5.4, 19.5, 22, 0, 3) // cab
    b.block(DARK, -1.6, 1.6, 20, 21, -26, -22) // trolley
    b.block(DARK, -0.3, 0.3, 14, 20, -25, -24)
    b.block(DARK, -1, 1, 13, 14, -26, -23) // hook
    return { boom: [0, 24, 6], trolley: [0, 21, -24] }
  },
}

const shipUnloader: ModelSpec = {
  key: 'SHIP_UNLOADER',
  label: 'Ship unloader',
  category: 'crane',
  size: { length: 24, width: 62, height: 35 },
  extraHeight: 0,
  mobile: false,
  author(b) {
    cornerLegs(b, STEEL, [-10, 10], [-4, 8], 0, 15, 1.6) // portal
    b.block(STEEL, -11, 11, 15, 17, -5, 9)
    b.block(AMBER, -5, 5, 17, 24, 0, 8) // hopper
    b.block(AMBER, -3, 3, 11, 17, 2, 6) // hopper chute
    b.lattice(BOOM, [0, 24, 8], [0, 35, -36], [1, 0, 0], 3) // boom toward the water
    b.beam(DARK, [0, 17, 14], [0, 24, 22], 1.6) // belt to the landside
    b.block(DARK, -1.4, 1.4, 17, 18, -2, 12) // belt over the portal
    b.block(WHITE, 5, 8, 17, 20, 4, 8) // cab
    return { boom: [0, 24, 8], hopper: [0, 20, 4] }
  },
}

const quayCraneRail: ModelSpec = {
  key: 'QUAY_CRANE_RAIL',
  label: 'Quay crane (rail grab)',
  category: 'crane',
  size: { length: 14, width: 50, height: 25 },
  extraHeight: 14,
  mobile: false,
  author(b) {
    const gauge = 10.5
    cornerLegs(b, ORANGE, [-6, 6], [0, gauge], 1, 25, 1.2) // 4 legs on two rails
    for (const z of [0, gauge]) b.block(DARK, -7, 7, 0, 1, z - 0.4, z + 0.4) // rails
    b.block(ORANGE, -6.6, 6.6, 25, 26.2, 0, gauge)
    b.beam(ORANGE, [-4, 26, 1], [0, 39, gauge / 2], 1) // A-frame to H + 14
    b.beam(ORANGE, [4, 26, 1], [0, 39, gauge / 2], 1)
    b.beam(ORANGE, [-4, 26, gauge - 1], [0, 39, gauge / 2], 1)
    b.beam(ORANGE, [4, 26, gauge - 1], [0, 39, gauge / 2], 1)
    b.lattice(BOOM, [0, 28, 20], [0, 31, -30], [1, 0, 0], 2.4) // backreach to outreach over the water
    b.block(STEEL, -3, 3, 26.2, 30, 0, 8) // machinery house
    b.block(WHITE, 3, 5, 26.2, 28.5, 3, 6) // cab
    b.block(DARK, -1.4, 1.4, 28, 29, -14, -10.5) // trolley
    b.block(DARK, -0.15, 0.15, 21, 28, -12.5, -12.2) // ropes
    b.block(DARK, -1, 1, 19.5, 21, -13.4, -11.2) // clamshell grab body
    b.block(AMBER, -1.3, 1.3, 17.5, 19.5, -14, -12.7) // amber jaws
    b.block(AMBER, -1.3, 1.3, 17.5, 19.5, -11.7, -10.4)
    return { boom: [0, 28, 6], trolley: [0, 28, -12], grab: [0, 21, -12] }
  },
}

const stsCrane: ModelSpec = {
  key: 'STS_CRANE',
  label: 'Ship-to-shore crane',
  category: 'crane',
  size: { length: 27, width: 70, height: 38 },
  extraHeight: 16,
  mobile: false,
  author(b) {
    const gauge = 16
    cornerLegs(b, ORANGE, [-12, 12], [0, gauge], 1, 38, 1.8) // legs, gauge 16 m
    for (const x of [-12, 12]) b.block(DARK, x - 1.2, x + 1.2, 0, 1, -1, gauge + 1) // bogies
    b.block(ORANGE, -13, 13, 38, 39.5, -1, gauge + 1)
    b.beam(ORANGE, [-12, 39, 0], [0, 54, 6], 1.2) // mast to H + 16
    b.beam(ORANGE, [12, 39, 0], [0, 54, 6], 1.2)
    b.beam(ORANGE, [-12, 39, gauge], [0, 54, 10], 1.2)
    b.beam(ORANGE, [12, 39, gauge], [0, 54, 10], 1.2)
    b.lattice(BOOM, [0, 41, 30], [0, 46, -40], [1, 0, 0], 3.4, 0.4) // backreach to outreach
    b.block(STEEL, -4, 4, 39.5, 43, 6, 14) // machinery house
    b.block(WHITE, 4, 6.4, 39.5, 42, 2, 5) // cab
    b.block(DARK, -2.4, 2.4, 44, 46, -26, -20) // trolley
    b.block(DARK, -0.25, 0.25, 34, 44, -24, -23)
    b.block(DARK, -1.3, 1.3, 33, 34, -29, -18) // spreader
    b.block(BLUE, -1.2, 1.2, 30.6, 33, -28, -19) // one blue box
    return { boom: [0, 41, 14], trolley: [0, 44, -23], spreader: [0, 33.5, -23.5] }
  },
}

const rtg: ModelSpec = {
  key: 'RTG',
  label: 'Rubber-tyred gantry',
  category: 'crane',
  size: { length: 12, width: 24, height: 18 },
  extraHeight: 0,
  mobile: false,
  author(b) {
    cornerLegs(b, AMBER, [-5.2, 5.2], [-11.2, 11.2], 1, 16.5, 1.2) // 4 legs
    for (const x of [-5.2, 5.2]) {
      b.block(AMBER, x - 0.8, x + 0.8, 16.5, 18, -12, 12) // top beams
      for (const z of [-11.2, 11.2]) b.block(DARK, x - 1.2, x + 1.2, 0, 1, z - 1.2, z + 1.2) // bogies
    }
    b.block(STEEL, -3.5, 3.5, 15, 16.5, -2, 2) // trolley
    b.block(WHITE, 5.2, 7, 14, 16.2, 8, 11) // cab
    b.block(DARK, -0.2, 0.2, 10.4, 15, -0.4, 0.4) // hoist ropes
    b.block(DARK, -1.3, 1.3, 9.6, 10.4, -6.2, 6.2) // spreader
    return { trolley: [0, 15.7, 0], spreader: [0, 10, 0] }
  },
}

export const CRANE_MODELS: ModelSpec[] = [
  mobileHarbourCrane,
  levelLuffingCrane,
  crawlerCrane,
  harbourGantryCrane,
  shipUnloader,
  quayCraneRail,
  stsCrane,
  rtg,
]
