import { Box3, BoxGeometry, EdgesGeometry, Vector3, type BufferGeometry } from 'three'
import type { ModelSize, ModelSpec } from './model-spec'
import { modelEnvelopeSize } from './registry'

/** An envelope is never flatter than this, metres: flat areas still get a visible box. */
export const MIN_ENVELOPE_HEIGHT = 0.3

/** The box an asset of this size must be drawn inside: footprint x height, at least 0.3 m high. */
export function envelopeSize(spec: ModelSpec | undefined, size: ModelSize): ModelSize {
  const drawn = spec ? modelEnvelopeSize(spec, size) : size
  return { ...drawn, height: Math.max(drawn.height, MIN_ENVELOPE_HEIGHT) }
}

/** Wireframe of a box on the ground: centred on x and z, standing on y = 0. */
export function envelopeEdges(size: ModelSize): BufferGeometry {
  const box = new BoxGeometry(size.length, size.height, size.width)
  box.translate(0, size.height / 2, 0)
  const edges = new EdgesGeometry(box)
  box.dispose()
  return edges
}

/**
 * Rims, end caps and fence posts are drawn centred on the edge of the footprint, so a model may reach this far
 * past it in plan (up to 0.5 m: the widest band is the terminal boundary), but never past its height.
 */
export const PLAN_TOLERANCE_M = 0.5
export const HEIGHT_TOLERANCE_M = 0.02

/** Whether a drawn box lies inside an envelope of the given size, centred on the footprint. */
export function fitsEnvelope(drawn: Box3, size: ModelSize): boolean {
  const limit = new Box3(
    new Vector3(
      -size.length / 2 - PLAN_TOLERANCE_M,
      -HEIGHT_TOLERANCE_M,
      -size.width / 2 - PLAN_TOLERANCE_M,
    ),
    new Vector3(
      size.length / 2 + PLAN_TOLERANCE_M,
      size.height + HEIGHT_TOLERANCE_M,
      size.width / 2 + PLAN_TOLERANCE_M,
    ),
  )
  return limit.containsBox(drawn)
}
