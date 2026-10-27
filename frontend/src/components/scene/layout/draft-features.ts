import { layoutFeatureSchema, type LayoutAsset, type LayoutFeature } from '@/lib/api'

/**
 * The assets of the draft as the features the 3D layers draw. Design shows the draft, so a shape drawn on the 2D
 * map stands in 3D at its height at once, also while its save is still on the way. Assets the layers cannot read
 * (a half-made optimistic one) are left out instead of breaking the scene.
 */
export function draftFeatures(assets: readonly LayoutAsset[]): LayoutFeature[] {
  const out: LayoutFeature[] = []
  for (const asset of assets) {
    const parsed = layoutFeatureSchema.safeParse({
      id: asset.id,
      type: 'Feature',
      geometry: asset.geometry,
      properties: { details: {}, computed: {}, ...asset, type: asset.typeCode },
    })
    if (parsed.success) out.push(parsed.data)
  }
  return out
}
