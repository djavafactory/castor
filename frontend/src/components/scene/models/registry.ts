import { BoxBatch, type Vec3 } from './box-batch'
import { BUILDING_MODELS } from './buildings'
import { BULK_MODELS } from './bulk'
import { CRANE_MODELS } from './cranes'
import { EQUIPMENT_MODELS } from './equipment'
import { LIQUID_MODELS } from './liquid'
import { MARINE_MODELS } from './marine'
import {
  tallest,
  type ModelCategory,
  type ModelParams,
  type ModelSize,
  type ModelSpec,
} from './model-spec'
import { STORAGE_MODELS } from './storage'
import { TRAFFIC_MODELS } from './traffic'
import { VEHICLE_MODELS } from './vehicles'
import { ZONE_MODELS } from './zones'
import { Mesh, type Group } from 'three'

/** The model library (parts 1 and 2: every structure, area and machine), keyed by model_key. */
export const MODEL_SPECS: readonly ModelSpec[] = [
  ...CRANE_MODELS,
  ...EQUIPMENT_MODELS,
  ...VEHICLE_MODELS,
  ...BUILDING_MODELS,
  ...STORAGE_MODELS,
  ...BULK_MODELS,
  ...LIQUID_MODELS,
  ...MARINE_MODELS,
  ...TRAFFIC_MODELS,
  ...ZONE_MODELS,
]

const BY_KEY = new Map(MODEL_SPECS.map((spec) => [spec.key, spec]))

export const CATEGORY_LABELS: Record<ModelCategory, string> = {
  crane: 'Cranes',
  handling: 'Handling equipment',
  vehicle: 'Vehicles',
  building: 'Buildings',
  storage: 'Storage',
  bulk: 'Bulk handling',
  liquid: 'Liquid and pipes',
  marine: 'Berths and quay',
  traffic: 'Gates and roads',
  zone: 'Zones and fences',
}

/** The model of a model_key, or undefined for a key the library does not know. */
export function getModelSpec(key: string): ModelSpec | undefined {
  return BY_KEY.get(key)
}

export interface BuiltModel {
  /** Meshes merged per colour, authored at the default size; scale it with `modelScale`. */
  group: Group
  /** Moving-part pivots in the same space as the group, metres. */
  pivots: Record<string, Vec3>
  /** Number of meshes: draw calls. */
  drawCalls: number
}

/** What a model is drawn from when the asset gives nothing: its default size, shape and details. */
export function defaultParams(spec: ModelSpec): ModelParams {
  return {
    size: spec.size,
    footprint: spec.footprint,
    path: spec.path,
    details: spec.details ?? {},
  }
}

/**
 * Draws a model: at its default size, footprint centred on the origin, standing on the ground, or - for a
 * parametric model - true to the size, footprint, path and details of the asset.
 */
export function buildModel(spec: ModelSpec, params: ModelParams = defaultParams(spec)): BuiltModel {
  const batch = new BoxBatch()
  const authored = spec.author(batch, params)
  const { group, map } = batch.build(
    spec.parametric
      ? null
      : { length: spec.size.length, width: spec.size.width, height: tallest(spec) },
  )
  const pivots = Object.fromEntries(Object.entries(authored).map(([name, p]) => [name, map(p)]))
  return { group, pivots, drawCalls: group.children.length }
}

/**
 * Scale from the default size to an asset's size: length / default length, width / default width,
 * height / default height. The boom and mast of a crane are part of the model, so they keep their height ratio.
 * Returned in scene axes: [x = length, y = height, z = width].
 */
export function modelScale(spec: ModelSpec, size: ModelSize): Vec3 {
  if (spec.parametric) return [1, 1, 1] // drawn at the asset's own size already
  return [
    size.length / spec.size.length,
    size.height / spec.size.height,
    size.width / spec.size.width,
  ]
}

/** Size of the drawn model of an asset of the given size: what the envelope (REPLICA-07) has to match. */
export function modelEnvelopeSize(spec: ModelSpec, size: ModelSize): ModelSize {
  if (spec.parametric) return size
  const [sx, sy, sz] = modelScale(spec, size)
  return {
    length: spec.size.length * sx,
    width: spec.size.width * sz,
    height: tallest(spec) * sy,
  }
}

/** Frees the geometry of a built model. The shared materials stay. */
export function disposeModel(group: Group) {
  for (const child of group.children) {
    if (child instanceof Mesh) child.geometry.dispose()
  }
}
