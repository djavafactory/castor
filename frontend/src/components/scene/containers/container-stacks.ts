import { hex, palette } from '@/features/scene/palette'

/** Static container fill of a container yard. Nothing moves a box and no box is stored: it is all derived. */

/** Colours of the container fleet, picked by colour key. */
export const CONTAINER_COLOURS = palette.containers.map(hex)

export const PAD_COLOUR = 0xb9b7ae

export const BAY_PITCH = 6.3
export const ROW_PITCH = 2.7
export const AISLE = 2.4
export const ROWS_PER_BLOCK = 7
/** Edge margin of the pad, metres: the first box starts this far from the yard edge. */
const EDGE = 2
/** Pad thickness: the first tier stands on it. */
export const PAD_TOP = 0.05
export const BOX_HEIGHT = 2.438
export const BOX_WIDTH = 2.591
export const BOX_LENGTH = { 20: 6.058, 40: 12.192 } as const
/** Boxes are drawn this much shorter and narrower than nominal, so neighbours never touch. */
export const DRAW_SHRINK = 0.12
const SKIP_CHANCE = 0.05
const FORTY_CHANCE = 0.5
const BRIGHTNESS_MIN = 0.9
const BRIGHTNESS_SPAN = 0.18

export type BoxSize = 20 | 40

export interface YardSpec {
  code: string
  /** Metres, along local +X. */
  length: number
  /** Metres, along local +Z. */
  width: number
  maxTiers: number
  /** Design fill, 0..1. */
  designFill: number
}

export interface ContainerBox {
  bay: number
  row: number
  /** 1-based. */
  tier: number
  size: BoxSize
  /** Centre of the drawn box in yard space (footprint centred on the origin), metres. */
  x: number
  y: number
  z: number
  colourKey: number
  brightness: number
}

/** FNV-1a, 32 bit. */
export function hashStr(text: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const bayCount = (length: number) => Math.max(0, Math.floor((length - EDGE) / BAY_PITCH))

/** Offset of each row from the first row's edge, metres: pitch 2.7, an aisle after every 7th row. */
export function rowOffsets(width: number): number[] {
  const usable = width - 2 * EDGE
  const offsets: number[] = []
  for (let r = 0; ; r++) {
    const start = r * ROW_PITCH + Math.floor(r / ROWS_PER_BLOCK) * AISLE
    if (start + ROW_PITCH > usable) break
    offsets.push(start)
  }
  return offsets
}

/** The boxes of a yard, the same on every call. Rows run along X, in order, then bay by bay, tier by tier. */
export function generateBoxes(yard: YardSpec): ContainerBox[] {
  const rnd = mulberry32(hashStr(yard.code))
  const bays = bayCount(yard.length)
  const rows = rowOffsets(yard.width)
  const tiers = Math.max(0, Math.floor(yard.maxTiers))
  const boxes: ContainerBox[] = []
  const x0 = -yard.length / 2 + EDGE / 2
  const z0 = -yard.width / 2 + EDGE

  rows.forEach((offset, row) => {
    const z = z0 + offset + ROW_PITCH / 2
    for (let bay = 0; bay < bays; bay++) {
      const raw = Math.round(yard.designFill * tiers + (rnd() - 0.5) * 2.4)
      const height = Math.min(tiers, Math.max(0, raw))
      const skip = rnd() < SKIP_CHANCE
      if (skip || height === 0) continue
      const forty = rnd() < FORTY_CHANCE && bay < bays - 1
      const colourKey = Math.floor(rnd() * CONTAINER_COLOURS.length)
      const brightness = BRIGHTNESS_MIN + rnd() * BRIGHTNESS_SPAN
      const size: BoxSize = forty ? 40 : 20
      const x = x0 + (bay + (forty ? 1 : 0.5)) * BAY_PITCH
      for (let tier = 1; tier <= height; tier++) {
        const y = PAD_TOP + (tier - 1) * BOX_HEIGHT + BOX_HEIGHT / 2
        boxes.push({ bay, row, tier, size, x, y, z, colourKey, brightness })
      }
      // A 40 ft box spans two bays: the next bay of this row is taken
      if (forty) bay++
    }
  })
  return boxes
}

/** Twenty-foot equivalent units: a 40 ft box counts two. */
export function teuOf(boxes: readonly ContainerBox[]): number {
  return boxes.reduce((sum, box) => sum + box.size / 20, 0)
}

/** Drawn size of a box, metres: [length, height, width]. */
export function drawnSize(size: BoxSize): [number, number, number] {
  return [BOX_LENGTH[size] - DRAW_SHRINK, BOX_HEIGHT, BOX_WIDTH - DRAW_SHRINK]
}
