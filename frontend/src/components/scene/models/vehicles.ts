import type { BoxBatch } from './box-batch'
import { COLOUR, type ModelSpec } from './model-spec'

const { STEEL, DARK, ORANGE, WHITE, BOOM, BLUE, AMBER, GLASS, COIL, CAR_RED } = COLOUR

interface TruckBase {
  /** x of the back of the cab: bodies sit behind it. */
  cabBack: number
  /** x of the rear end of the chassis. */
  rear: number
}

/**
 * The shared truck base: a chassis, a cab at the front (+X) and wheels on three axles. Bodies are added by
 * each truck type. Uses DARK for the chassis and wheels and GLASS for the windows.
 */
function truckBase(b: BoxBatch, length: number, cabColour: number, cabHeight = 3.4): TruckBase {
  const front = length / 2
  const rear = -length / 2
  b.block(DARK, rear, front, 0.7, 1.1, -0.9, 0.9) // chassis rails
  b.block(cabColour, front - 2.3, front, 1.1, cabHeight, -1.25, 1.25) // cab
  b.block(GLASS, front - 0.1, front, cabHeight - 1.5, cabHeight - 0.4, -1.05, 1.05) // windscreen
  for (const x of [front - 1.4, rear + 1.2, rear + 2.7]) {
    for (const z of [-1.1, 1.1]) b.cylinder(DARK, 'z', [x, 0.55, z], 0.55, 0.4, 10)
  }
  return { cabBack: front - 2.3, rear }
}

const flatbedTruck: ModelSpec = {
  key: 'FLATBED_TRUCK',
  label: 'Flatbed truck',
  category: 'vehicle',
  size: { length: 12.5, width: 2.55, height: 3.4 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    const { cabBack, rear } = truckBase(b, 12.5, BLUE)
    b.block(STEEL, rear, cabBack - 0.2, 1.1, 1.3, -1.275, 1.275) // deck
    for (const x of [-4.4, -2.2, 0]) b.cylinder(COIL, 'x', [x, 2.2, 0], 0.9, 1.6, 12) // 3 coils
    return { deck: [0, 1.3, 0] }
  },
}

const lowBedTrailer: ModelSpec = {
  key: 'LOW_BED_TRAILER',
  label: 'Low-bed trailer',
  category: 'vehicle',
  size: { length: 17, width: 3, height: 3.4 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    const { cabBack, rear } = truckBase(b, 17, ORANGE)
    b.block(STEEL, rear, cabBack - 4, 0.45, 0.85, -1.5, 1.5) // low deck
    b.block(STEEL, cabBack - 4, cabBack - 0.2, 0.85, 1.6, -1.2, 1.2) // gooseneck
    b.block(DARK, rear - 0.1, rear + 0.3, 0.2, 0.8, -1.5, 1.5) // rear ramp
    for (const x of [rear + 1.2, rear + 2.4, rear + 3.6]) {
      for (const z of [-1.4, 1.4]) b.cylinder(DARK, 'z', [x, 0.4, z], 0.4, 0.4, 10)
    }
    return { gooseneck: [cabBack - 2, 1.2, 0] }
  },
}

const dumpTruck: ModelSpec = {
  key: 'DUMP_TRUCK',
  label: 'Dump truck',
  category: 'vehicle',
  size: { length: 9, width: 2.55, height: 3.8 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    const { cabBack, rear } = truckBase(b, 9, ORANGE, 3.2)
    b.block(AMBER, rear, cabBack - 0.2, 1.2, 3.4, -1.275, 1.275) // tipping body
    b.block(AMBER, cabBack - 1.2, cabBack - 0.2, 3.4, 3.8, -1.275, 1.275) // headboard
    return { body: [rear + 0.4, 1.2, 0] }
  },
}

const tankTruck: ModelSpec = {
  key: 'TANK_TRUCK',
  label: 'Tank truck',
  category: 'vehicle',
  size: { length: 10, width: 2.55, height: 3.6 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    const { cabBack, rear } = truckBase(b, 10, BLUE, 3.4)
    b.cylinder(BOOM, 'x', [(rear + cabBack) / 2 - 0.1, 2.3, 0], 1.2, cabBack - rear - 0.5, 14) // tank
    b.block(BOOM, rear + 1.5, rear + 2.5, 3.5, 3.6, -0.4, 0.4) // manway
    return { tank: [0, 2.3, 0] }
  },
}

/** A car for a transporter deck: body and cabin, lying along X. */
function car(b: BoxBatch, x: number, y: number, z: number, body: number) {
  b.block(body, x - 2.1, x + 2.1, y + 0.25, y + 0.85, z - 0.8, z + 0.8)
  b.block(GLASS, x - 1.1, x + 0.9, y + 0.85, y + 1.35, z - 0.7, z + 0.7)
}

const roroTransporter: ModelSpec = {
  key: 'RORO_TRANSPORTER',
  label: 'RoRo car transporter',
  category: 'vehicle',
  size: { length: 18, width: 2.6, height: 4.4 },
  extraHeight: 0,
  mobile: true,
  author(b) {
    const { cabBack, rear } = truckBase(b, 18, ORANGE)
    const decks = [1.1, 2.9]
    for (const y of decks) b.block(DARK, rear, cabBack - 0.2, y, y + 0.15, -1.3, 1.3) // 2 decks
    for (const x of [rear + 0.1, cabBack - 0.3]) {
      for (const z of [-1.25, 1.25]) b.block(DARK, x - 0.05, x + 0.05, 1.1, 4.4, z - 0.05, z + 0.05)
    }
    b.block(DARK, rear, cabBack - 0.2, 4.3, 4.4, -1.3, 1.3) // roof frame
    const colours = [WHITE, CAR_RED, STEEL]
    for (const [d, y] of decks.entries()) {
      for (let i = 0; i < 3; i++) car(b, rear + 2.4 + i * 4.6, y + 0.15, 0, colours[(i + d) % 3]!)
    }
    return { deckLower: [0, 1.1, 0], deckUpper: [0, 2.9, 0] }
  },
}

export const VEHICLE_MODELS: ModelSpec[] = [
  flatbedTruck,
  lowBedTrailer,
  dumpTruck,
  tankTruck,
  roroTransporter,
]
