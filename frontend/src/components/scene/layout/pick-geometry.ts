import { BoxGeometry, type BufferGeometry } from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import type { PickBox } from './placement'

/** A box has 12 triangles. */
const TRIANGLES_PER_BOX = 12

export interface PickGeometry {
  /** Every pick box in one geometry: one invisible mesh, one draw call, whatever the number of assets. */
  geometry: BufferGeometry
  /** The asset behind a triangle that a ray hit. */
  assetAtFace: (faceIndex: number) => string | undefined
}

/** Merges the pick boxes into one geometry. Null when there is nothing to pick. */
export function buildPickGeometry(boxes: readonly PickBox[]): PickGeometry | null {
  if (boxes.length === 0) return null
  const parts = boxes.map((b) =>
    new BoxGeometry(b.size.length, b.size.height, b.size.width)
      .rotateY(b.rotationY)
      .translate(...b.position),
  )
  const geometry = mergeGeometries(parts, false)
  parts.forEach((g) => g.dispose())
  return {
    geometry,
    assetAtFace: (faceIndex) => boxes[Math.floor(faceIndex / TRIANGLES_PER_BOX)]?.assetId,
  }
}
