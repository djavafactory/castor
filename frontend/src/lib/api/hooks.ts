import { useQuery } from '@tanstack/react-query'
import { layoutQueryOptions, staticQueryOptions } from '@/app/query-client'
import { getEnv } from '@/lib/env'
import {
  accuracyReportSchema,
  assetHistorySchema,
  assetTypesSchema,
  catalogueSchema,
  controlPointsSchema,
  layoutSchema,
  layoutVersionsSchema,
  portSchema,
  referenceFeaturesSchema,
  searchResultsSchema,
  type ReferenceKind,
} from './schemas'
import { getParsed } from './validated'

/** Query keys in one place so mutations and tests invalidate exactly what hooks read. */
export const queryKeys = {
  port: (code: string) => ['port', code] as const,
  controlPoints: (code: string) => ['control-points', code] as const,
  referenceFeatures: (code: string, kinds: readonly ReferenceKind[]) =>
    ['reference-features', code, [...kinds].sort().join(',')] as const,
  assetTypes: () => ['asset-types'] as const,
  catalogue: () => ['catalogue'] as const,
  published: (code: string) => ['layout', code, 'published'] as const,
  draft: (code: string) => ['layout', code, 'draft'] as const,
  versions: (code: string) => ['layout', code, 'versions'] as const,
  assetHistory: (code: string, assetId: string) => ['asset-history', code, assetId] as const,
  accuracyReport: (code: string) => ['accuracy-report', code] as const,
  search: (code: string, q: string) => ['search', code, q] as const,
}

/** The port of this deployment (VITE_PORT_CODE); hooks accept a code for tests and for other ports. */
export function defaultPortCode(): string {
  return getEnv().VITE_PORT_CODE
}

const portPath = (code: string) => `/v1/ports/${encodeURIComponent(code)}`

// ---- Static data: fetched once, kept until the page is reloaded ----

export function usePort(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.port(code),
    queryFn: ({ signal }) => getParsed(portPath(code), portSchema, { signal }),
    ...staticQueryOptions,
  })
}

export function useControlPoints(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.controlPoints(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/control-points`, controlPointsSchema, { signal }),
    ...staticQueryOptions,
  })
}

/** Background shapes (OpenStreetMap) of the given kinds, for example ['BUILDING', 'ROAD']. */
export function useReferenceFeatures(
  kinds: readonly ReferenceKind[],
  code: string = defaultPortCode(),
) {
  return useQuery({
    queryKey: queryKeys.referenceFeatures(code, kinds),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/reference-features`, referenceFeaturesSchema, {
        signal,
        query: { kind: kinds.length > 0 ? [...kinds].join(',') : undefined },
      }),
    ...staticQueryOptions,
  })
}

export function useAssetTypes() {
  return useQuery({
    queryKey: queryKeys.assetTypes(),
    queryFn: ({ signal }) => getParsed('/v1/asset-types', assetTypesSchema, { signal }),
    ...staticQueryOptions,
  })
}

export function useCatalogue() {
  return useQuery({
    queryKey: queryKeys.catalogue(),
    queryFn: ({ signal }) => getParsed('/v1/catalogue', catalogueSchema, { signal }),
    ...staticQueryOptions,
  })
}

// ---- Layout data: refetched whenever it is used again ----

export function usePublishedLayout(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.published(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/layout/published`, layoutSchema, { signal }),
    ...layoutQueryOptions,
  })
}

export function useDraftLayout(code: string = defaultPortCode(), enabled = true) {
  return useQuery({
    queryKey: queryKeys.draft(code),
    queryFn: ({ signal }) => getParsed(`${portPath(code)}/layout/draft`, layoutSchema, { signal }),
    enabled,
    ...layoutQueryOptions,
  })
}

export function useLayoutVersions(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.versions(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/layout/versions`, layoutVersionsSchema, { signal }),
    ...layoutQueryOptions,
  })
}

export function useAssetHistory(assetId: string | null, code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.assetHistory(code, assetId ?? ''),
    queryFn: ({ signal }) =>
      getParsed(
        `${portPath(code)}/assets/${encodeURIComponent(assetId ?? '')}/history`,
        assetHistorySchema,
        { signal },
      ),
    enabled: assetId !== null,
    ...layoutQueryOptions,
  })
}

export function useAccuracyReport(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.accuracyReport(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/accuracy-report`, accuracyReportSchema, { signal }),
    ...layoutQueryOptions,
  })
}

/** Search the layout. Nothing is sent until the query has at least 2 characters. */
export function useSearch(q: string, code: string = defaultPortCode()) {
  const term = q.trim()
  return useQuery({
    queryKey: queryKeys.search(code, term),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/search`, searchResultsSchema, { signal, query: { q: term } }),
    enabled: term.length >= 2,
    ...layoutQueryOptions,
  })
}
