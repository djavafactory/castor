import { fromLonLat, type Local } from '@/lib/geo'
import type { ReferenceFeature } from '@/lib/reference-api'
import { DEFAULT_HEIGHT_M } from '../buildings/building-geometry'

/** Outer ring of a reference polygon in local metres, and the height it is drawn at. Needs the active frame. */
export function footprintOf(feature: ReferenceFeature): { ring: Local[]; height: number } | null {
  const c = feature.geometry.coordinates as number[][][] | number[][][][]
  const outer =
    feature.geometry.type === 'Polygon'
      ? (c as number[][][])[0]
      : feature.geometry.type === 'MultiPolygon'
        ? (c as number[][][][])[0]?.[0]
        : undefined
  if (!outer || outer.length < 3) return null
  const height =
    feature.properties.heightM && feature.properties.heightM > 0
      ? feature.properties.heightM
      : DEFAULT_HEIGHT_M
  return { ring: outer.map((p) => fromLonLat(p[0], p[1])), height }
}
