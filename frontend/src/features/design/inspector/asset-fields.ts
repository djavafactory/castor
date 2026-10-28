import type { AssetType, LayoutAsset } from '@/lib/api'
import type { components } from '@/types/api'

type Feature = components['schemas']['LayoutFeatureProperties']
export type LayoutDetails = components['schemas']['LayoutDetails']

/**
 * A draft asset as the Inspector reads it: the feature properties under their wire names, next to id, type and
 * geometry. The draft is read by flattening the feature (see lib/api/schemas.ts), so the names are the same.
 */
export type AssetFields = LayoutAsset &
  Feature & { geometry: { type: string; coordinates: unknown } }

export const fieldsOf = (asset: LayoutAsset): AssetFields => asset as AssetFields

/** How the geometry of a type is edited: rectangles and points, circles, berths, drawn outlines and lines. */
export type ShapeKind = 'rect' | 'circle' | 'berth' | 'quay-point' | 'polygon' | 'line'

export function shapeKindOf(type: AssetType): ShapeKind {
  switch (type.geometryKind) {
    case 'QUAY_SEGMENT':
      return 'berth'
    case 'CIRCLE':
      return 'circle'
    case 'LINE':
      return 'line'
    case 'POLYGON':
      return 'polygon'
    default:
      // rail cranes stand on a berth and slide along the quay; other points and rectangles are sized boxes
      return type.flags.mustBeOnBerth ? 'quay-point' : 'rect'
  }
}

/** Storage types (the STORAGE palette group) carry a cargo, capacity and a storage zone. */
export const isStorageType = (type: AssetType) => type.paletteGroup === 'STORAGE'

/** Equipment that moves or lifts: it has a model, an operator, a throughput and a design status. */
export const isEquipmentType = (type: AssetType) =>
  ['CRANES', 'HANDLING', 'TRANSPORT'].includes(type.paletteGroup)

export const LINK_LABELS: Record<string, string> = {
  SERVES: 'Serves',
  FEEDS: 'Feeds',
  BELONGS_TO: 'Belongs to',
  CONNECTS_TO: 'Connects to',
}

export const LINK_KINDS = Object.keys(LINK_LABELS) as (keyof typeof LINK_LABELS)[]

/** The stored coordinates of a polygon's outer ring, without the closing point; null for other geometries. */
export function ringOf(geometry: {
  type: string
  coordinates: unknown
}): [number, number][] | null {
  if (geometry.type !== 'Polygon' || !Array.isArray(geometry.coordinates)) return null
  const ring = geometry.coordinates[0]
  if (!Array.isArray(ring) || ring.length < 4) return null
  return (ring as [number, number][]).slice(0, -1)
}

/** The stored vertices of a line string; null for other geometries. */
export function linePointsOf(geometry: {
  type: string
  coordinates: unknown
}): [number, number][] | null {
  if (geometry.type !== 'LineString' || !Array.isArray(geometry.coordinates)) return null
  return geometry.coordinates as [number, number][]
}

/**
 * The links a delete removes: the ones this asset has and the ones other assets have to it. Named in the confirmation so
 * the editor sees what goes with the asset.
 */
export function dependentsOf(asset: AssetFields, assets: readonly AssetFields[]): string[] {
  const name = (id: string) => assets.find((a) => a.id === id)?.name ?? 'an asset'
  const outgoing = asset.links.map(
    (l) => `${asset.name} ${LINK_LABELS[l.kind]} ${name(l.toAssetId)}`,
  )
  const incoming = assets
    .filter((a) => a.id !== asset.id)
    .flatMap((a) =>
      a.links
        .filter((l) => l.toAssetId === asset.id)
        .map((l) => `${a.name} ${LINK_LABELS[l.kind]} ${asset.name}`),
    )
  return [...outgoing, ...incoming]
}
