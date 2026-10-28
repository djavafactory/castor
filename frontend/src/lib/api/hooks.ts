import { useQuery } from '@tanstack/react-query'
import { layoutQueryOptions, staticQueryOptions } from '@/app/query-client'
import { apiGet } from '@/lib/api-client'
import { getEnv } from '@/lib/env'
import {
  accuracyReportSchema,
  assetHistorySchema,
  assetTypesSchema,
  catalogueSchema,
  controlPointsSchema,
  layoutDocumentSchema,
  layoutDiffSchema,
  currentLayoutSchema,
  layoutVersionsSchema,
  reviewersSchema,
  validationSchema,
  portSchema,
  referenceFeaturesSchema,
  searchResultsSchema,
  type ReferenceKind,
} from './schemas'
import { getParsed, requestParsed } from './validated'

/** Query keys in one place so mutations and tests invalidate exactly what hooks read. */
export const queryKeys = {
  port: (code: string) => ['port', code] as const,
  controlPoints: (code: string) => ['control-points', code] as const,
  referenceFeatures: (code: string, kinds: readonly ReferenceKind[]) =>
    ['reference-features', code, [...kinds].sort().join(',')] as const,
  assetTypes: () => ['asset-types'] as const,
  catalogue: () => ['catalogue'] as const,
  current: (code: string) => ['layout', code, 'current'] as const,
  published: (code: string) => ['layout', code, 'published'] as const,
  draft: (code: string) => ['layout', code, 'draft'] as const,
  versions: (code: string) => ['layout', code, 'versions'] as const,
  assetHistory: (code: string, assetId: string) => ['asset-history', code, assetId] as const,
  /** The signature changes whenever the draft changes, so each save asks the server for the diff again. */
  diff: (layoutId: string, againstId: string, signature: string) =>
    ['layout-diff', layoutId, againstId, signature] as const,
  reviewers: () => ['reviewers'] as const,
  /** The signature changes whenever the draft changes, so every save asks the server again. */
  validation: (layoutId: string, signature: string) => ['validation', layoutId, signature] as const,
  accuracyReport: (layoutId: string) => ['accuracy-report', layoutId] as const,
  search: (code: string, q: string, layoutId: string | null) =>
    ['search', code, q, layoutId] as const,
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

export function useCatalogue(enabled = true) {
  return useQuery({
    queryKey: queryKeys.catalogue(),
    queryFn: ({ signal }) => getParsed('/v1/catalogue', catalogueSchema, { signal }),
    enabled,
    ...staticQueryOptions,
  })
}

// ---- Layout data: refetched whenever it is used again ----

/** How often Monitor asks whether a newer version was published (no WebSocket in Phase 1). */
export const CURRENT_LAYOUT_POLL_MS = 60_000

/** The published layout version of the port, with its ETag. Read-only; every signed-in role may read it. */
export function useCurrentLayout(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.current(code),
    queryFn: ({ signal }) =>
      requestParsed(`${portPath(code)}/layouts/current`, currentLayoutSchema, { signal }),
    ...layoutQueryOptions,
  })
}

/**
 * Watches the ETag of the published layout. It is true once the server answers with a different ETag than the
 * one the screen was drawn from, which means a newer version was published. Only the header is read.
 */
export function useNewLayoutVersion(loadedEtag: string | null, code: string = defaultPortCode()) {
  const { data } = useQuery({
    queryKey: [...queryKeys.current(code), 'etag'],
    queryFn: ({ signal }) =>
      apiGet<unknown>(`${portPath(code)}/layouts/current`, { signal }).then((r) => r.etag),
    enabled: loadedEtag !== null,
    refetchInterval: CURRENT_LAYOUT_POLL_MS,
    staleTime: 0,
    retry: false,
  })
  return loadedEtag !== null && data != null && data !== loadedEtag
}

export function usePublishedLayout(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.published(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/layouts/current`, layoutDocumentSchema, { signal }),
    ...layoutQueryOptions,
  })
}

/** The draft is shared by the team: while it is on screen it is read again on focus and every 30 s. */
export const DRAFT_SYNC_MS = 30_000

export function fetchDraftLayout(code: string, signal?: AbortSignal) {
  return getParsed(`${portPath(code)}/layouts/draft`, layoutDocumentSchema, { signal })
}

export function useDraftLayout(
  code: string = defaultPortCode(),
  enabled = true,
  options: { sync?: boolean } = {},
) {
  return useQuery({
    queryKey: queryKeys.draft(code),
    queryFn: ({ signal }) => fetchDraftLayout(code, signal),
    enabled,
    ...layoutQueryOptions,
    ...(options.sync ? { refetchOnWindowFocus: true, refetchInterval: DRAFT_SYNC_MS } : {}),
  })
}

/** The versions of the port, newest first. Drafts and discarded versions come with it for editors. */
export function useLayoutVersions(code: string = defaultPortCode(), enabled = true) {
  return useQuery({
    queryKey: queryKeys.versions(code),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/layouts`, layoutVersionsSchema, {
        signal,
        query: { limit: 100 },
      }),
    enabled,
    ...layoutQueryOptions,
  })
}

/** One asset in every version that holds it, newest first. */
export function useAssetHistory(assetId: string | null, code: string = defaultPortCode()) {
  return useQuery({
    queryKey: queryKeys.assetHistory(code, assetId ?? ''),
    queryFn: ({ signal }) =>
      getParsed(`/v1/assets/${encodeURIComponent(assetId ?? '')}/history`, assetHistorySchema, {
        signal,
      }),
    enabled: assetId !== null,
    ...layoutQueryOptions,
  })
}

/** What the version has that another lacks (added), lacks (removed) and changed. Waits for both ids. */
export function useLayoutDiff(
  layoutId: string | null,
  againstId: string | null,
  signature = '',
  enabled = true,
) {
  return useQuery({
    queryKey: queryKeys.diff(layoutId ?? '', againstId ?? '', signature),
    queryFn: ({ signal }) =>
      getParsed(`/v1/layouts/${encodeURIComponent(layoutId ?? '')}/diff`, layoutDiffSchema, {
        signal,
        query: { against: againstId ?? undefined },
      }),
    enabled: enabled && layoutId !== null && againstId !== null,
    ...layoutQueryOptions,
  })
}

/** Who may be named as reviewer in the publish dialog (PUBLISHER and ADMIN may ask). */
export function useReviewers(enabled = true) {
  return useQuery({
    queryKey: queryKeys.reviewers(),
    queryFn: ({ signal }) => getParsed('/v1/reviewers', reviewersSchema, { signal }),
    enabled,
    ...layoutQueryOptions,
  })
}

/** The full list of errors and warnings of a version. A POST, but read-only: nothing is stored and nothing audited. */
export function fetchValidation(layoutId: string, signal?: AbortSignal) {
  return requestParsed(`/v1/layouts/${encodeURIComponent(layoutId)}/validate`, validationSchema, {
    method: 'POST',
    signal,
  }).then((r) => r.data)
}

/**
 * Control point residuals and shape counts by accuracy class of a version (DESIGNER-11). Run from the More menu with
 * `queryClient.fetchQuery` under `queryKeys.accuracyReport`, so the Issues tab's ACCURACY CHECK section can show the
 * same answer without asking the server again.
 */
export function fetchAccuracyReport(layoutId: string, signal?: AbortSignal) {
  return getParsed(
    `/v1/layouts/${encodeURIComponent(layoutId)}/accuracy-report`,
    accuracyReportSchema,
    {
      signal,
    },
  )
}

/**
 * Search the port's own data (port, control points, the assets of one layout, map shapes). Nothing is sent until the
 * text has at least 2 characters, and nothing is kept: the answer is dropped as soon as the box no longer shows it.
 */
export function useSearch(
  q: string,
  code: string = defaultPortCode(),
  layoutId: string | null = null,
) {
  const term = q.trim()
  return useQuery({
    queryKey: queryKeys.search(code, term, layoutId),
    queryFn: ({ signal }) =>
      getParsed(`${portPath(code)}/search`, searchResultsSchema, {
        signal,
        query: { q: term, layoutId: layoutId ?? undefined },
      }),
    enabled: term.length >= 2,
    staleTime: 0,
    gcTime: 0,
  })
}
