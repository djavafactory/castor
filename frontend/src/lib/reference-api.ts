import { useQuery } from '@tanstack/react-query'
import { getEnv } from '@/lib/env'
import { referenceFeaturesSchema, type ReferenceKind } from '@/lib/api/schemas'
import { getParsed } from '@/lib/api/validated'

export type ReferenceFeatureKind = ReferenceKind

/** One background shape as the 3D layers use it (the API sends snake_case properties; see {@link fetchReferenceFeatures}). */
export interface ReferenceFeature {
  type: 'Feature'
  id: string
  geometry: { type: string; coordinates: unknown }
  properties: {
    kind: ReferenceKind
    osmRef: string
    name: string | null
    heightM: number | null
    areaM2: number | null
    source: string
    accuracy: 'SURVEYED' | 'VERIFIED' | 'OPERATOR' | 'APPROXIMATE'
  }
}

/** Read-only OpenStreetMap features of the port. There is no write call for them. */
export function fetchReferenceFeatures(
  portCode: string,
  kind: ReferenceFeatureKind,
  signal?: AbortSignal,
): Promise<ReferenceFeature[]> {
  return getParsed(
    `/v1/ports/${encodeURIComponent(portCode)}/reference-features`,
    referenceFeaturesSchema,
    {
      query: { kind },
      signal,
    },
  ).then((collection) =>
    collection.features.map((f) => ({
      type: 'Feature',
      id: f.properties.osm_ref,
      geometry: { type: f.geometry.type, coordinates: f.geometry.coordinates },
      properties: {
        kind: f.properties.kind,
        osmRef: f.properties.osm_ref,
        name: f.properties.name,
        heightM: f.properties.height_m,
        areaM2: f.properties.area_m2,
        source: f.properties.source,
        accuracy: f.properties.accuracy,
      },
    })),
  )
}

/** The OSM buildings of the configured port; reference data changes only on re-import, so it is kept for a while. */
export function useReferenceBuildings(enabled = true) {
  const code = getEnv().VITE_PORT_CODE
  return useQuery({
    queryKey: ['reference-features', code, 'BUILDING'],
    queryFn: ({ signal }) => fetchReferenceFeatures(code, 'BUILDING', signal),
    enabled,
    staleTime: 5 * 60_000,
  })
}
