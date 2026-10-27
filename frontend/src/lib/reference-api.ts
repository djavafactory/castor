import { useQuery } from '@tanstack/react-query'
import { apiGet } from '@/lib/api-client'
import { getEnv } from '@/lib/env'
import type { components } from '@/types/api'

export type ReferenceFeature = components['schemas']['ReferenceFeature']
export type ReferenceFeatureCollection = components['schemas']['ReferenceFeatureCollection']
export type ReferenceFeatureKind = components['schemas']['ReferenceFeatureKind']

/** Read-only OpenStreetMap features of the port. There is no write call for them. */
export function fetchReferenceFeatures(
  portCode: string,
  kind: ReferenceFeatureKind,
  signal?: AbortSignal,
): Promise<ReferenceFeature[]> {
  return apiGet<ReferenceFeatureCollection>(`/v1/ports/${portCode}/reference-features`, {
    query: { kind },
    signal,
  }).then((r) => r.data.features)
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
