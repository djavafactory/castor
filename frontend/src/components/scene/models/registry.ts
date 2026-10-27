import { BoxBatch, type Vec3 } from './box-batch'
import { CRANE_MODELS } from './cranes'
import { EQUIPMENT_MODELS } from './equipment'
import { tallest, type ModelCategory, type ModelSize, type ModelSpec } from './model-spec'
import { VEHICLE_MODELS } from './vehicles'
import { Mesh, type Group } from 'three'

/** The model library part 1, keyed by model_key. */
export const MODEL_SPECS: readonly ModelSpec[] = [
  ...CRANE_MODELS,
  ...EQUIPMENT_MODELS,
  ...VEHICLE_MODELS,
]

const BY_KEY = new Map(MODEL_SPECS.map((spec) => [spec.key, spec]))

export const CATEGORY_LABELS: Record<ModelCategory, string> = {
  crane: 'Cranes',
  handling: 'Handling equipment',
  vehicle: 'Vehicles',
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

/** Draws a model at its default size: footprint centred on the origin, standing on the ground. */
export function buildModel(spec: ModelSpec): BuiltModel {
  const batch = new BoxBatch()
  const authored = spec.author(batch)
  const { group, map } = batch.build({
    length: spec.size.length,
    width: spec.size.width,
    height: tallest(spec),
  })
  const pivots = Object.fromEntries(Object.entries(authored).map(([name, p]) => [name, map(p)]))
  return { group, pivots, drawCalls: group.children.length }
}

/**
 * Scale from the default size to an asset's size: length / default length, width / default width,
 * height / default height. The boom and mast of a crane are part of the model, so they keep their height ratio.
 * Returned in scene axes: [x = length, y = height, z = width].
 */
export function modelScale(spec: ModelSpec, size: ModelSize): Vec3 {
  return [
    size.length / spec.size.length,
    size.height / spec.size.height,
    size.width / spec.size.width,
  ]
}

/** Size of the drawn model of an asset of the given size: what the envelope (REPLICA-07) has to match. */
export function modelEnvelopeSize(spec: ModelSpec, size: ModelSize): ModelSize {
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
