import { BufferGeometry, Euler, Float32BufferAttribute, Matrix4, Quaternion, Vector3 } from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { envelopeEdges } from '../models/envelope'
import type { AccuracyOutline, PickBox } from './placement'

/** Outlines sit this far above the ground so they are not lost in it, metres. */
export const OUTLINE_LIFT_M = 0.6
/** Dash and gap of an APPROXIMATE outline, metres. */
export const DASH_M = 3
export const GAP_M = 2

/** One wireframe of every envelope box, each turned with its asset. Null when there are none. */
export function buildEnvelopeGeometry(boxes: readonly PickBox[]): BufferGeometry | null {
  const edges = boxes.map((b) => {
    const geometry = envelopeEdges(b.size)
    // The box stands on y = 0 in its own frame; the pick position is its centre
    geometry.translate(0, -b.size.height / 2, 0)
    geometry.applyMatrix4(
      new Matrix4().compose(
        new Vector3(...b.position),
        new Quaternion().setFromEuler(new Euler(0, b.rotationY, 0)),
        new Vector3(1, 1, 1),
      ),
    )
    return geometry
  })
  if (edges.length === 0) return null
  const merged = mergeGeometries(edges, false)
  edges.forEach((e) => e.dispose())
  return merged
}

/** Accuracy classes that have outlines, with their line segments (pairs of points) and the dashed flag. */
export interface OutlineBatch {
  accuracy: string
  dashed: boolean
  geometry: BufferGeometry
}

/** One line geometry per accuracy class; APPROXIMATE ones are drawn dashed. */
export function buildOutlineBatches(outlines: readonly AccuracyOutline[]): OutlineBatch[] {
  const byAccuracy = new Map<string, number[]>()
  for (const outline of outlines) {
    const pts = outline.points
    const last = outline.closed ? pts.length : pts.length - 1
    const list = byAccuracy.get(outline.accuracy) ?? []
    for (let i = 0; i < last; i++) {
      const [ax, az] = pts[i]!
      const [bx, bz] = pts[(i + 1) % pts.length]!
      list.push(ax, OUTLINE_LIFT_M, az, bx, OUTLINE_LIFT_M, bz)
    }
    byAccuracy.set(outline.accuracy, list)
  }
  return [...byAccuracy].map(([accuracy, positions]) => {
    const geometry = new BufferGeometry()
    geometry.setAttribute('position', new Float32BufferAttribute(positions, 3))
    // The dashes need line distances: the layer computes them when it draws a dashed material
    const dashed = accuracy === 'APPROXIMATE'
    return { accuracy, dashed, geometry }
  })
}
