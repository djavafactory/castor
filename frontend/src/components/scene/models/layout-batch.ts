import {
  Group,
  Matrix4,
  Mesh,
  Quaternion,
  Vector3,
  type BufferGeometry,
  type Material,
} from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import type { Pt } from './box-batch'
import type { ModelDetails, ModelSize } from './model-spec'
import { buildModel, disposeModel, getModelSpec, modelScale } from './registry'

/** One placed asset of a layout. */
export interface LayoutAsset {
  modelKey: string
  size: ModelSize
  footprint?: Pt[]
  path?: Pt[]
  details?: ModelDetails
  /** Scene position of the footprint centre, metres (y = elevation above ground). */
  position: [x: number, y: number, z: number]
  /** Turn about the up axis, radians. */
  rotationY?: number
}

export interface LayoutCaption {
  text: string
  /** Scene position above the asset. */
  position: [x: number, y: number, z: number]
}

export interface LayoutMeshes {
  /** One mesh per material: the whole layout is a handful of draw calls, not one per asset. */
  group: Group
  captions: LayoutCaption[]
  /** Meshes in the group plus one sprite per caption. */
  drawCalls: number
  /** Model keys the library does not know; the caller draws those itself. */
  unknown: string[]
}

/**
 * Merges every known asset of a layout, in place, into one mesh per material. Per-asset groups would cost
 * a draw call per colour per asset; this keeps a full terminal far below the budget.
 */
export function buildLayoutMeshes(assets: readonly LayoutAsset[]): LayoutMeshes {
  const byMaterial = new Map<Material, { geometries: BufferGeometry[]; renderOrder: number }>()
  const captions: LayoutCaption[] = []
  const unknown: string[] = []

  for (const asset of assets) {
    const spec = getModelSpec(asset.modelKey)
    if (!spec) {
      unknown.push(asset.modelKey)
      continue
    }
    const params = {
      size: asset.size,
      footprint: asset.footprint,
      path: asset.path,
      details: asset.details ?? {},
    }
    const built = buildModel(spec, spec.parametric ? params : undefined)
    const place = new Matrix4().compose(
      new Vector3(...asset.position),
      new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), asset.rotationY ?? 0),
      new Vector3(...modelScale(spec, asset.size)),
    )
    for (const mesh of built.group.children as Mesh[]) {
      const entry = byMaterial.get(mesh.material as Material) ?? {
        geometries: [],
        renderOrder: mesh.renderOrder,
      }
      entry.geometries.push(mesh.geometry.clone().applyMatrix4(place))
      byMaterial.set(mesh.material as Material, entry)
    }
    disposeModel(built.group)

    const caption = spec.caption?.(params)
    if (caption) {
      const [x, y, z] = asset.position
      captions.push({ text: caption.text, position: [x, y + caption.height, z] })
    }
  }

  const group = new Group()
  for (const [material, { geometries, renderOrder }] of byMaterial) {
    const merged = mergeGeometries(geometries, false)
    geometries.forEach((g) => g.dispose())
    merged.computeBoundingSphere()
    const mesh = new Mesh(merged, material)
    mesh.renderOrder = renderOrder
    group.add(mesh)
  }
  return { group, captions, drawCalls: group.children.length + captions.length, unknown }
}
