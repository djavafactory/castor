import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { z } from 'zod'
import { apiRequest } from '@/lib/api-client'
import { defaultPortCode } from './hooks'
import { getParsed, parseResponse } from './validated'

/**
 * The Integrations page (INGEST-04): what each source sent, what was refused and why, API keys and identity mappings.
 * Browsers call castor-core only (R7); core asks the gateway. Everything here is OPERATOR and ADMIN; only ADMIN acts.
 */

// ---- Vocabulary ----

/** The sources of a port, as in the gateway. `canonical` takes events that are already canonical. */
export const INTEGRATION_SOURCES = ['canonical', 'cbas', 'itap', 'scss', 'edic', 'sensors'] as const
/** The systems an identity mapping can name (the canonical source needs none). */
export const MAPPING_SOURCES = ['cbas', 'itap', 'scss', 'edic', 'sensors'] as const
export const MAPPING_ENTITY_TYPES = [
  'EQUIPMENT',
  'STORAGE',
  'BERTH',
  'GATE',
  'VESSEL_CALL',
] as const

export type MappingSource = (typeof MAPPING_SOURCES)[number]
export type MappingEntityType = (typeof MAPPING_ENTITY_TYPES)[number]

// ---- Schemas ----

const instant = z.string()

export const freshnessSchema = z.enum(['FRESH', 'STALE', 'UNKNOWN'])
export type Freshness = z.infer<typeof freshnessSchema>

export const integrationSourceSchema = z.object({
  code: z.string(),
  enabled: z.boolean(),
  requireSignature: z.boolean(),
  qualityDefault: z.string(),
  activeKeys: z.number().int(),
  accepted: z.number(),
  duplicate: z.number(),
  quarantined: z.number(),
  lastReceivedAt: instant.nullish(),
  freshness: freshnessSchema,
  freshnessSlaSeconds: z.number(),
})
export type IntegrationSource = z.infer<typeof integrationSourceSchema>

export const integrationSourcesSchema = z.object({ items: z.array(integrationSourceSchema) })

export const integrationKeySchema = z.object({
  id: z.number().int(),
  prefix: z.string(),
  origin: z.enum(['issued', 'env']),
  createdAt: instant,
  revokedAt: instant.nullish(),
  /** The whole key: only in the answer that creates it. */
  key: z.string().nullish(),
})
export type IntegrationKey = z.infer<typeof integrationKeySchema>

export const integrationKeysSchema = z.object({ items: z.array(integrationKeySchema) })

export const replayStatusSchema = z.enum(['RECEIVED', 'NORMALIZED', 'QUARANTINED'])
export type ReplayStatus = z.infer<typeof replayStatusSchema>

export const quarantineEntrySchema = z.object({
  id: z.number().int(),
  receptionId: z.string(),
  source: z.string(),
  sourceEventId: z.string(),
  reasonCode: z.string(),
  detail: z.string(),
  receivedAt: instant,
  quarantinedAt: instant,
  /** Set when this entry is a replay that was refused again: the original record. */
  replayOf: z.string().nullish(),
  replays: z.number().int(),
  latestReplayStatus: replayStatusSchema.nullish(),
  subjectType: z.string().nullish(),
  subjectId: z.string().nullish(),
})
export type QuarantineEntry = z.infer<typeof quarantineEntrySchema>

export const quarantinePageSchema = z.object({
  items: z.array(quarantineEntrySchema),
  nextBefore: z.number().nullish(),
})

export const replayAttemptSchema = z.object({
  receptionId: z.string(),
  receivedAt: instant,
  status: replayStatusSchema,
  reasonCode: z.string().nullish(),
  detail: z.string().nullish(),
  events: z.number().int(),
  published: z.number().int(),
})
export type ReplayAttempt = z.infer<typeof replayAttemptSchema>

export const quarantineDetailSchema = z.object({
  entry: quarantineEntrySchema,
  /** The payload as received with keys and secrets masked; null once removed. */
  raw: z.unknown(),
  rawAvailable: z.boolean(),
  attempts: z.array(replayAttemptSchema),
})
export type QuarantineDetail = z.infer<typeof quarantineDetailSchema>

export const replayResultSchema = z.object({ receptionId: z.string(), replayOf: z.string() })
export type ReplayResult = z.infer<typeof replayResultSchema>

export const identityMappingSchema = z.object({
  id: z.number().int(),
  sourceSystem: z.string(),
  sourceEntityType: z.string(),
  sourceId: z.string(),
  canonicalId: z.string(),
  effectiveFrom: instant,
  effectiveTo: instant.nullish(),
  createdBy: z.string(),
  createdAt: instant,
})
export type IdentityMapping = z.infer<typeof identityMappingSchema>

export const identityMappingsSchema = z.object({ items: z.array(identityMappingSchema) })

export const unresolvedIdSchema = z.object({
  source: z.string(),
  entityType: z.string(),
  sourceId: z.string(),
  reasonCode: z.string(),
  records: z.number(),
  lastSeenAt: instant,
})
export type UnresolvedId = z.infer<typeof unresolvedIdSchema>

export const unresolvedIdsSchema = z.object({ items: z.array(unresolvedIdSchema) })

// ---- Queries ----

export interface QuarantineFilter {
  source?: string
  reason?: string
}

export interface MappingFilter {
  source?: string
  entityType?: string
  sourceId?: string
  state?: 'open' | 'closed' | 'all'
}

/** How many quarantine entries one page asks for. */
export const QUARANTINE_PAGE_SIZE = 50

const basePath = (code: string) => `/v1/ports/${encodeURIComponent(code)}/integrations`

export const integrationKeys = {
  all: (code: string) => ['integrations', code] as const,
  sources: (code: string) => ['integrations', code, 'sources'] as const,
  keys: (code: string, source: string) => ['integrations', code, 'keys', source] as const,
  quarantine: (code: string, filter: QuarantineFilter) =>
    ['integrations', code, 'quarantine', filter.source ?? '', filter.reason ?? ''] as const,
  quarantined: (code: string, receptionId: string) =>
    ['integrations', code, 'quarantined', receptionId] as const,
  mappings: (code: string, filter: MappingFilter) =>
    [
      'integrations',
      code,
      'mappings',
      filter.source ?? '',
      filter.entityType ?? '',
      filter.sourceId ?? '',
      filter.state ?? 'all',
    ] as const,
  unresolved: (code: string) => ['integrations', code, 'unresolved'] as const,
}

/** The counters change by the minute: refetch when the page is used again and every half minute while it is open. */
const LIVE = { staleTime: 0, refetchInterval: 30_000 } as const

export function useIntegrationSources(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: integrationKeys.sources(code),
    queryFn: ({ signal }) =>
      getParsed(`${basePath(code)}/sources`, integrationSourcesSchema, { signal }),
    ...LIVE,
  })
}

export function useIntegrationKeys(source: string | null, code: string = defaultPortCode()) {
  return useQuery({
    queryKey: integrationKeys.keys(code, source ?? ''),
    queryFn: ({ signal }) =>
      getParsed(
        `${basePath(code)}/sources/${encodeURIComponent(source ?? '')}/keys`,
        integrationKeysSchema,
        { signal },
      ),
    enabled: source !== null,
    staleTime: 0,
  })
}

/** The quarantine, newest first, a page at a time (keyset): `fetchNextPage` loads the older ones. */
export function useQuarantine(filter: QuarantineFilter, code: string = defaultPortCode()) {
  return useInfiniteQuery({
    queryKey: integrationKeys.quarantine(code, filter),
    queryFn: ({ signal, pageParam }) =>
      getParsed(`${basePath(code)}/quarantine`, quarantinePageSchema, {
        signal,
        query: {
          source: filter.source || undefined,
          reason: filter.reason || undefined,
          before: pageParam,
          limit: QUARANTINE_PAGE_SIZE,
        },
      }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => last.nextBefore ?? undefined,
    staleTime: 0,
    // A replay waits for the normalizer: look again every second while one is on its way, else every half minute
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) =>
        page.items.some((entry) => entry.latestReplayStatus === 'RECEIVED'),
      )
        ? 1_000
        : LIVE.refetchInterval,
  })
}

export function useQuarantineDetail(receptionId: string | null, code: string = defaultPortCode()) {
  return useQuery({
    queryKey: integrationKeys.quarantined(code, receptionId ?? ''),
    queryFn: ({ signal }) =>
      getParsed(
        `${basePath(code)}/quarantine/${encodeURIComponent(receptionId ?? '')}`,
        quarantineDetailSchema,
        { signal },
      ),
    enabled: receptionId !== null,
    staleTime: 0,
    // A replay waits for the normalizer and the relay: look again while one is still on its way
    refetchInterval: (query) =>
      query.state.data?.attempts.some((a) => a.status === 'RECEIVED' || a.published < a.events)
        ? 1_000
        : false,
  })
}

export function useIdentityMappings(filter: MappingFilter, code: string = defaultPortCode()) {
  return useQuery({
    queryKey: integrationKeys.mappings(code, filter),
    queryFn: ({ signal }) =>
      getParsed(`${basePath(code)}/mappings`, identityMappingsSchema, {
        signal,
        query: {
          source: filter.source || undefined,
          entityType: filter.entityType || undefined,
          sourceId: filter.sourceId || undefined,
          state: filter.state && filter.state !== 'all' ? filter.state : undefined,
        },
      }),
    staleTime: 0,
  })
}

export function useUnresolvedIds(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: integrationKeys.unresolved(code),
    queryFn: ({ signal }) =>
      getParsed(`${basePath(code)}/mappings/unresolved`, unresolvedIdsSchema, { signal }),
    ...LIVE,
  })
}

// ---- Actions (ADMIN) ----

function refresh(client: QueryClient, code: string) {
  return client.invalidateQueries({ queryKey: integrationKeys.all(code) })
}

/** Puts a quarantined record through the pipeline again. The original is not changed: the result is a new record. */
export function useReplayRecord(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (receptionId: string): Promise<ReplayResult> => {
      const path = `${basePath(code)}/quarantine/${encodeURIComponent(receptionId)}/replay`
      const response = await apiRequest<unknown>(path, { method: 'POST' })
      return parseResponse(path, replayResultSchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}

/** The new key is in the result once; nothing can read it again. */
export function useCreateIntegrationKey(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (source: string): Promise<IntegrationKey> => {
      const path = `${basePath(code)}/sources/${encodeURIComponent(source)}/keys`
      const response = await apiRequest<unknown>(path, { method: 'POST' })
      return parseResponse(path, integrationKeySchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}

/** Revokes the key and issues a new one; the old key stops working at once. */
export function useRotateIntegrationKey(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { source: string; keyId: number }): Promise<IntegrationKey> => {
      const path = `${basePath(code)}/sources/${encodeURIComponent(input.source)}/keys/${input.keyId}/rotate`
      const response = await apiRequest<unknown>(path, { method: 'POST' })
      return parseResponse(path, integrationKeySchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}

export function useRevokeIntegrationKey(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { source: string; keyId: number }): Promise<IntegrationKey> => {
      const path = `${basePath(code)}/sources/${encodeURIComponent(input.source)}/keys/${input.keyId}`
      const response = await apiRequest<unknown>(path, { method: 'DELETE' })
      return parseResponse(path, integrationKeySchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}

export interface NewMapping {
  sourceSystem: MappingSource
  sourceEntityType: MappingEntityType
  sourceId: string
  canonicalId: string
  /** ISO-8601 instant; the server defaults it to the epoch, so every event of the source id maps. */
  effectiveFrom?: string
  effectiveTo?: string
}

export function useCreateIdentityMapping(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (mapping: NewMapping): Promise<IdentityMapping> => {
      const path = `${basePath(code)}/mappings`
      const response = await apiRequest<unknown>(path, { method: 'POST', body: mapping })
      return parseResponse(path, identityMappingSchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}

/** Ends a mapping (now, or at `effectiveTo`); it is never deleted. */
export function useCloseIdentityMapping(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { id: number; effectiveTo?: string }): Promise<IdentityMapping> => {
      const path = `${basePath(code)}/mappings/${input.id}/close`
      const response = await apiRequest<unknown>(path, {
        method: 'POST',
        body: input.effectiveTo ? { effectiveTo: input.effectiveTo } : {},
      })
      return parseResponse(path, identityMappingSchema, response.data)
    },
    onSettled: () => refresh(client, code),
  })
}
