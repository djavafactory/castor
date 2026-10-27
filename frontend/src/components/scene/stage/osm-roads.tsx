import { useMemo } from 'react'
import type { Local } from '@/lib/geo'
import { fromLonLat } from '@/lib/geo'
import type { ReferenceFeature } from '@/lib/reference-api'
import { osmRoad, type FlatItem } from './flat-geometry'
import { FlatLayers } from './flat-layers'

/** Default width of a road whose OSM record has none, metres. */
const DEFAULT_WIDTH_M = 7

function lines(f: ReferenceFeature): number[][][] {
  const c = f.geometry.coordinates as number[][] | number[][][]
  if (f.geometry.type === 'LineString') return [c as number[][]]
  if (f.geometry.type === 'MultiLineString') return c as number[][][]
  return []
}

/** The flat ribbons of the OSM roads, in local metres. Needs the active frame. */
export function osmRoadItems(roads: readonly ReferenceFeature[]): FlatItem[] {
  return roads.flatMap((road) =>
    lines(road).flatMap((line) => {
      const points: Local[] = line.map((p) => fromLonLat(p[0], p[1]))
      return osmRoad(points, road.properties.widthM ?? DEFAULT_WIDTH_M)
    }),
  )
}

export function OsmRoadsLayer({ roads }: { roads: readonly ReferenceFeature[] }) {
  const items = useMemo(() => osmRoadItems(roads), [roads])
  return <FlatLayers items={items} name="roads-osm" />
}
