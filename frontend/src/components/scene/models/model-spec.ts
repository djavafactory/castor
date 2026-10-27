import type { BoxBatch, Vec3 } from './box-batch'

/** Colours of the model library. */
export const COLOUR = {
  STEEL: 0x6b7480,
  DARK: 0x2b3442,
  ORANGE: 0xf2622e,
  WHITE: 0xffffff,
  BOOM: 0xe9e5dc,
  BLUE: 0x3c6fb4,
  AMBER: 0xe8a317,
  GLASS: 0x9db7cc,
  COIL: 0xb8c0c8,
  CAR_RED: 0xb5382f,
  /** Unknown model keys. */
  NEUTRAL: 0xc9bfa3,
  /** Uncertainty ring of parked mobile equipment. */
  RING: 0x4f6d8f,
} as const

export const RING_OPACITY = 0.55

/** An asset's size in metres, as stored on the asset. */
export interface ModelSize {
  length: number
  width: number
  height: number
}

export type ModelCategory = 'crane' | 'handling' | 'vehicle'

/** Names of the moving parts and where they turn, in model space of the default size, metres. */
export type Pivots = Record<string, Vec3>

/**
 * One entry of the model library. Every model stands on the ground with its footprint centred on the origin;
 * local +X is the length (heading) axis, +Y is up, the width runs along Z. Water-side booms point to -Z,
 * which is local +Y of the quay frame.
 */
export interface ModelSpec {
  /** The asset's model_key. */
  key: string
  label: string
  category: ModelCategory
  /** Size the model is authored at; larger or smaller assets scale it. */
  size: ModelSize
  /** Structure above `size.height` (A-frame, mast): the drawn model is that much taller. Scales with height. */
  extraHeight: number
  /** Mobile equipment gets an uncertainty ring while PARKED. */
  mobile: boolean
  /** Draws the shapes at roughly the default size and returns the pivots, in the same space. */
  author: (batch: BoxBatch) => Pivots
}

export function tallest(spec: ModelSpec): number {
  return spec.size.height + spec.extraHeight
}
