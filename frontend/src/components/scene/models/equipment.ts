import type { BoxBatch } from './box-batch'
import { COLOUR, type ModelSpec } from './model-spec'

const { STEEL, DARK, ORANGE, WHITE, AMBER } = COLOUR

/** A wheel across the width axis: one cylinder per side. */
function wheelPair(b: BoxBatch, x: number, radius: number, halfTrack: number, thickness: number) {
  for (const z of [-halfTrack, halfTrack])
    b.cylinder(DARK, 'z', [x, radius, z], radius, thickness, 12)
}

const forklift: ModelSpec = {
  key: 'FORKLIFT',
  label: 'Forklift',
  category: 'handling',
  size: { length: 5, width: 2.2, height: 3 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(AMBER, -2.5, 1.4, 0.4, 1.4, -1, 1) // body
    b.block(AMBER, -2.5, -1.6, 1.4, 1.8, -0.9, 0.9) // counterweight
    b.block(DARK, -0.6, 0.4, 1.4, 1.8, -0.4, 0.4) // seat
    for (const z of [-0.9, 0.9]) {
      b.block(DARK, 1.4, 1.9, 0, 3, z - 0.1, z + 0.1) // mast
      b.block(DARK, 1.8, 2.5, 0, 0.12, z - 0.15, z + 0.15) // forks
    }
    for (const x of [-1.1, 0.5])
      for (const z of [-0.9, 0.9]) b.block(DARK, x - 0.05, x + 0.05, 1.4, 2.8, z - 0.05, z + 0.05)
    b.block(DARK, -1.2, 0.6, 2.8, 3, -1, 1) // overhead guard
    wheelPair(b, 0.9, 0.45, 0.8, 0.4)
    wheelPair(b, -1.8, 0.4, 0.8, 0.4)
    return { mast: [1.65, 0.1, 0] }
  },
}

const reachStacker: ModelSpec = {
  key: 'REACH_STACKER',
  label: 'Reach stacker',
  category: 'handling',
  size: { length: 9.5, width: 4.2, height: 4.3 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(ORANGE, -4.75, 2, 0.9, 2.4, -1.6, 1.6) // chassis
    b.block(WHITE, 0.4, 2, 2.4, 4.3, -1.6, -0.2) // cab
    b.beam(STEEL, [-3.5, 2.2, 0.5], [3, 3.4, 0.5], 0.7) // boom, lowered
    b.beam(STEEL, [-3.5, 2.2, -0.5], [3, 3.4, -0.5], 0.7)
    b.block(DARK, 2.6, 4.75, 2.9, 3.4, -1.3, 1.3) // spreader
    for (const x of [-3.4, 0.6]) {
      b.block(DARK, x - 0.7, x + 0.7, 0, 1.9, -2.1, -1.4)
      b.block(DARK, x - 0.7, x + 0.7, 0, 1.9, 1.4, 2.1)
    }
    return { boom: [-3.5, 2.2, 0], spreader: [3.7, 3.1, 0] }
  },
}

const wheelLoader: ModelSpec = {
  key: 'WHEEL_LOADER',
  label: 'Wheel loader',
  category: 'handling',
  size: { length: 9.5, width: 3.2, height: 3.7 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(AMBER, -4.75, -0.2, 0.7, 2.4, -1.3, 1.3) // rear frame with engine
    b.block(AMBER, -0.2, 2.2, 0.7, 1.6, -1.2, 1.2) // front frame
    b.block(WHITE, -2.6, -0.4, 2.4, 3.7, -1.1, 1.1) // cab
    b.beam(STEEL, [0.4, 1.8, 1.1], [3.2, 1.1, 1.1], 0.4) // lift arms
    b.beam(STEEL, [0.4, 1.8, -1.1], [3.2, 1.1, -1.1], 0.4)
    b.block(DARK, 3, 4.75, 0.25, 1.8, -1.6, 1.6) // bucket
    for (const x of [-2.6, 1.2]) {
      b.cylinder(DARK, 'z', [x, 0.9, -1.35], 0.9, 0.7, 12)
      b.cylinder(DARK, 'z', [x, 0.9, 1.35], 0.9, 0.7, 12)
    }
    return { arm: [0.4, 1.8, 0], bucket: [3.2, 1.1, 0] }
  },
}

const terminalTractor: ModelSpec = {
  key: 'TERMINAL_TRACTOR',
  label: 'Terminal tractor',
  category: 'handling',
  size: { length: 6, width: 2.5, height: 3.2 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(DARK, -3, 3, 0.7, 1.1, -0.9, 0.9) // chassis
    b.block(ORANGE, 0.6, 3, 1.1, 3.2, -1.25, 1.25) // cab
    b.block(WHITE, 2.9, 3, 1.9, 2.9, -1, 1) // windscreen
    b.block(STEEL, -2.6, -0.4, 1.1, 1.4, -1.1, 1.1) // fifth wheel
    for (const x of [-2.1, 1.8]) wheelPair(b, x, 0.6, 1.05, 0.5)
    return { fifthWheel: [-1.5, 1.4, 0] }
  },
}

const excavator: ModelSpec = {
  key: 'EXCAVATOR',
  label: 'Excavator',
  category: 'handling',
  size: { length: 9.5, width: 3.2, height: 3.4 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(DARK, -2.4, 2.4, 0, 1, -1.6, -0.5) // tracked base
    b.block(DARK, -2.4, 2.4, 0, 1, 0.5, 1.6)
    b.block(DARK, -1.4, 1.4, 0.6, 1.1, -1, 1)
    b.block(AMBER, -2.6, 1.2, 1.1, 2.5, -1.3, 1.3) // house
    b.block(WHITE, 0, 1.2, 2.5, 3.4, -1.3, -0.1) // cab
    b.beam(AMBER, [1, 1.8, 0], [3.6, 3.2, 0], 0.7) // boom
    b.beam(AMBER, [3.6, 3.2, 0], [6.7, 0.9, 0], 0.5) // arm
    b.block(DARK, 6.2, 6.7, 0, 1.1, -0.7, 0.7) // bucket
    return { boom: [1, 1.8, 0], arm: [3.6, 3.2, 0], bucket: [6.5, 0.9, 0] }
  },
}

const mobileConveyorLoader: ModelSpec = {
  key: 'MOBILE_CONVEYOR_LOADER',
  label: 'Mobile conveyor loader',
  category: 'handling',
  size: { length: 14, width: 3, height: 5.5 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    b.block(STEEL, -3, 4, 0.8, 1.5, -1.4, 1.4) // wheeled chassis
    for (const x of [-1.5, 2.8]) wheelPair(b, x, 0.7, 1.2, 0.5)
    b.beam(DARK, [-7, 1.4, 0], [5, 4.8, 0], 1.2) // inclined belt
    b.beam(STEEL, [-7, 0.9, 0.9], [5, 4.2, 0.9], 0.3) // belt frame
    b.beam(STEEL, [-7, 0.9, -0.9], [5, 4.2, -0.9], 0.3)
    b.block(AMBER, -7.2, -5.4, 1, 2.6, -1.3, 1.3) // feed hopper
    b.block(AMBER, 5, 6.6, 3.3, 5.5, -0.8, 0.8) // discharge spout
    b.block(WHITE, 2.4, 4, 1.5, 2.8, -1.4, -0.4) // operator cab
    return { belt: [-7, 1.4, 0], spout: [5.8, 4.8, 0] }
  },
}

export const EQUIPMENT_MODELS: ModelSpec[] = [
  forklift,
  reachStacker,
  wheelLoader,
  terminalTractor,
  excavator,
  mobileConveyorLoader,
]
