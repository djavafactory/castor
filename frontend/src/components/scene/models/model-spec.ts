import { hex, palette } from '@/features/scene/palette'
import type { BoxBatch, Pt, Vec3 } from './box-batch'

/** Colours of the model library. */
export const COLOUR = {
  STEEL: hex(palette.containers[5]),
  DARK: hex(palette.steel),
  ORANGE: hex(palette.signal),
  WHITE: 0xffffff,
  BOOM: 0xe9e5dc,
  BLUE: 0x3c6fb4,
  AMBER: hex(palette.amber),
  GLASS: 0x9db7cc,
  COIL: 0xb8c0c8,
  CAR_RED: 0xb5382f,
  /** Unknown model keys, and the walls of buildings. */
  NEUTRAL: hex(palette.warehouseWall),
  /** Uncertainty ring of parked mobile equipment. */
  RING: 0x4f6d8f,
  /** Roof and floor of warehouses. */
  ROOF: 0x8c7a5a,
  /** Storage pads and yards. */
  PAD: hex(palette.pad),
  /** Tarmac of roads, gates and holding areas. */
  ROAD: 0x4a5059,
  CONCRETE: 0xb9b4a8,
  SILO: 0xc9cdd2,
  GAUGE: 0xe2be5a,
  HOPPER: hex(palette.amber),
  TANK: 0xb9c4cc,
  PIPE: hex(palette.brick),
  RAMP: 0x8c949e,
  ZONE_RED: hex(palette.brick),
  ZONE_BLUE: 0x6fa8c9,
  PILE: 0x8a6d4b,
  STACK: 0x5d7f9a,
  PAVING: 0xd8d4c8,
} as const

export const RING_OPACITY = 0.55

/** An asset's size in metres, as stored on the asset. */
export interface ModelSize {
  length: number
  width: number
  height: number
}

export type ModelCategory =
  | 'crane'
  | 'handling'
  | 'vehicle'
  | 'building'
  | 'storage'
  | 'bulk'
  | 'liquid'
  | 'marine'
  | 'traffic'
  | 'zone'

/** Cargo form of a storage pad: how its design fill is drawn. */
export type CargoForm = 'PILE' | 'STACK' | 'SILO'

/** The asset details the shapes depend on. Everything is optional: a model has a default for each. */
export interface ModelDetails {
  /** Dock doors of a warehouse. */
  doors?: number
  /** Roof rise of a gabled roof, metres. */
  roofRise?: number
  /** Slot grid of a storage pad. */
  bays?: number
  rows?: number
  /** Design fill, 0..1: share of slots, coil positions or parking bays filled; level of a gauge. */
  designFill?: number
  cargoForm?: CargoForm
  /** Berth or pad name, and the water depth of a berth, metres. */
  name?: string
  depth?: number
  /** The berth has rail quay cranes: crane rails are drawn. */
  railCranes?: boolean
}

/**
 * What a model is drawn from. Sizes are metres in model space: +X is the length, +Z the width, the footprint
 * is centred on the origin. `footprint` is the drawn polygon of an area asset, `path` the polyline of a linear
 * one, both around the same centre; without them the model draws the size rectangle or a straight line.
 */
export interface ModelParams {
  size: ModelSize
  footprint?: Pt[]
  path?: Pt[]
  details: ModelDetails
}

/** A text drawn above a model: berth name, storage pad name. */
export interface ModelCaption {
  text: string
  /** Height above the ground, metres. */
  height: number
}

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
  /** Size the model is authored at; larger or smaller assets scale it (all, unless `parametric`). */
  size: ModelSize
  /** Structure above `size.height` (A-frame, mast): the drawn model is that much taller. Scales with height. */
  extraHeight: number
  /** Mobile equipment gets an uncertainty ring while PARKED. */
  mobile: boolean
  /**
   * Parametric models are authored at the asset's own size, footprint, path and details and drawn true to
   * them, never scaled. Their default look comes from `footprint`, `path` and `details` below.
   */
  parametric?: boolean
  footprint?: Pt[]
  path?: Pt[]
  details?: ModelDetails
  /** Draws the shapes (at roughly the default size, unless `parametric`) and returns the pivots, same space. */
  author: (batch: BoxBatch, params: ModelParams) => Pivots
  /** Text above the model. */
  caption?: (params: ModelParams) => ModelCaption | null
}

export function tallest(spec: ModelSpec): number {
  return spec.size.height + spec.extraHeight
}
