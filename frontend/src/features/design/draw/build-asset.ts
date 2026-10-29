import { rectangle, type LonLat } from '@/lib/geo'
import type { AssetType } from '@/lib/api'
import type { NewAsset } from '@/lib/api'
import type { Basemap } from '@/lib/basemap'
import {
  provenanceFor,
  sketchAnchor,
  sketchGeometry,
  sketchStats,
  type DrawShape,
} from './draw-rules'

/** Details groups the layout rules require for a type, taken from the type's own defaults. */
function detailsFor(type: AssetType): Record<string, unknown> | undefined {
  const params = type.params
  const details: Record<string, unknown> = {}
  if (type.code === 'EXCLUSION_ZONE') {
    details.zone = { allowedTypes: Array.isArray(params.allowedTypes) ? params.allowedTypes : [] }
  }
  if (type.code === 'CONVEYOR' || type.code === 'PIPELINE') {
    details.flow = { rateValue: params.rateTph ?? null, rateUnit: params.rateTph ? 'tph' : null }
  }
  if (type.flags.mobile) details.mobile = { positionMode: 'PARKED', uncertaintyM: null }
  return Object.keys(details).length > 0 ? details : undefined
}

interface Identity {
  name: string
  /** Left out, the server gives the next free code of the port: codes outlive the layouts they were first used in. */
  code?: string
}

/** The body of POST asset for a traced polygon or line; every coordinate is the rounded value that was drawn. */
export function tracedAsset(
  type: AssetType,
  shape: DrawShape,
  points: readonly LonLat[],
  identity: Identity,
  basemap: Basemap,
): NewAsset {
  const stats = sketchStats(shape, points)
  return {
    typeCode: type.code,
    ...identity,
    geometry: sketchGeometry(shape, points),
    anchor: sketchAnchor(stats.local),
    heightM: type.defaults.heightM,
    ...(shape === 'line' ? { lineWidthM: type.defaults.lineWidthM } : {}),
    details: detailsFor(type),
    provenance: provenanceFor(basemap),
  }
}

/** A point type placed by one click: its default footprint, upright, around the clicked point. */
export function placedAsset(
  type: AssetType,
  at: LonLat,
  identity: Identity,
  basemap: Basemap,
): NewAsset {
  const length = type.defaults.lengthM ?? 1
  const width = type.defaults.widthM ?? 1
  const ring = rectangle(at, 0, length, width).map(([lon, lat]) => [lon, lat])
  return {
    typeCode: type.code,
    ...identity,
    geometry: { type: 'Polygon', coordinates: [[...ring, ring[0]!]] },
    anchor: at,
    headingDeg: 0,
    lengthM: length,
    widthM: width,
    heightM: type.defaults.heightM,
    details: detailsFor(type),
    provenance: provenanceFor(basemap),
  }
}
